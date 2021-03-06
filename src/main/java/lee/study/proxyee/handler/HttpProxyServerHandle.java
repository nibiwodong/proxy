package lee.study.proxyee.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;
import java.util.LinkedList;
import java.util.List;
import lee.study.proxyee.crt.CertPool;
import lee.study.proxyee.exception.HttpProxyExceptionHandle;
import lee.study.proxyee.intercept.HttpProxyIntercept;
import lee.study.proxyee.intercept.HttpProxyInterceptInitializer;
import lee.study.proxyee.intercept.HttpProxyInterceptPipeline;
import lee.study.proxyee.proxy.ProxyConfig;
import lee.study.proxyee.proxy.ProxyHandleFactory;
import lee.study.proxyee.server.HttpProxyServer;
import lee.study.proxyee.server.HttpProxyServerConfig;
import lee.study.proxyee.util.ProtoUtil;
import lee.study.proxyee.util.ProtoUtil.RequestProto;

public class HttpProxyServerHandle extends ChannelInboundHandlerAdapter {

  private ChannelFuture cf;
  private String host;
  private int port;
  private boolean isSsl = false;
  private int status = 0;
  private HttpProxyServerConfig serverConfig;
  private ProxyConfig proxyConfig;
  private HttpProxyInterceptInitializer interceptInitializer;
  private HttpProxyInterceptPipeline interceptPipeline;
  private HttpProxyExceptionHandle exceptionHandle;
  private List requestList;
  private boolean isConnect;

  public HttpProxyServerConfig getServerConfig() {
    return serverConfig;
  }

  public HttpProxyInterceptPipeline getInterceptPipeline() {
    return interceptPipeline;
  }

  public HttpProxyExceptionHandle getExceptionHandle() {
    return exceptionHandle;
  }

  public HttpProxyServerHandle(HttpProxyServerConfig serverConfig,
      HttpProxyInterceptInitializer interceptInitializer,
      ProxyConfig proxyConfig, HttpProxyExceptionHandle exceptionHandle) {
    this.serverConfig = serverConfig;
    this.proxyConfig = proxyConfig;
    this.interceptInitializer = interceptInitializer;
    this.exceptionHandle = exceptionHandle;
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
    RequestProto requestProto = null;
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if (status == 0) {
        requestProto = ProtoUtil.getRequestProto(request);
        if (requestProto == null) { //bad request
          ctx.channel().close();
          return;
        }
        status = 1;
        this.host = requestProto.getHost();
        this.port = requestProto.getPort();
        if ("CONNECT".equalsIgnoreCase(request.method().name())) {//建立代理握手
          status = 2;
          HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
              HttpProxyServer.SUCCESS);
          ctx.writeAndFlush(response);
          ctx.channel().pipeline().remove("httpCodec");
          return;
        }
      }
      interceptPipeline = buildPiepeline();
      interceptPipeline.setRequestProto(new RequestProto(host, port, isSsl));
      interceptPipeline.beforeRequest(ctx.channel(), request, interceptPipeline.getRequestProto());
    } else if (msg instanceof HttpContent) {
      if (status != 2) {
        interceptPipeline.beforeRequest(ctx.channel(), (HttpContent) msg, requestProto);
      } else {
        ReferenceCountUtil.release(msg);
        status = 1;
      }
    } else {
      ByteBuf byteBuf = (ByteBuf) msg;
      if (byteBuf.getByte(0) == 22) {
        isSsl = true;
        SslContext sslCtx = SslContextBuilder
            .forServer(serverConfig.getServerPriKey(), CertPool.getCert(this.host, serverConfig))
            .build();
        ctx.pipeline().addFirst("httpCodec", new HttpServerCodec());
        ctx.pipeline().addFirst("sslHandle", sslCtx.newHandler(ctx.alloc()));
        ctx.pipeline().fireChannelRead(msg);
        return;
      }
      handleProxyData(ctx.channel(), msg, false);
    }
  }

  @Override
  public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
    if (cf != null) {
      cf.channel().close();
    }
    ctx.channel().close();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (cf != null) {
      cf.channel().close();
    }
    ctx.channel().close();
    exceptionHandle.beforeCatch(ctx.channel(), cause);
  }

  private void handleProxyData(Channel channel, Object msg, boolean isHttp)
      throws Exception {
    if (cf == null) {
      if (isHttp && !(msg instanceof HttpRequest)) {
        return;
      }
      ProxyHandler proxyHandler = ProxyHandleFactory.build(proxyConfig);
      RequestProto requestProto = new RequestProto(host, port, isSsl);
      ChannelInitializer channelInitializer =
          isHttp ? new HttpProxyInitializer(channel, requestProto, proxyHandler)
              : new TunnelProxyInitializer(channel, proxyHandler);
      Bootstrap bootstrap = new Bootstrap();
      bootstrap.group(serverConfig.getLoopGroup())
          .channel(NioSocketChannel.class)
          .handler(channelInitializer);
      if (proxyConfig != null) {
        bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
      }
      requestList = new LinkedList();
      cf = bootstrap.connect(host, port);
      cf.addListener((ChannelFutureListener) future -> {
        if (future.isSuccess()) {
          future.channel().writeAndFlush(msg);
          synchronized (requestList) {
            requestList.forEach((obj) -> future.channel().writeAndFlush(obj));
            requestList.clear();
            isConnect = true;
          }
        } else {
          requestList.forEach((obj) -> ReferenceCountUtil.release(obj));
          requestList.clear();
          future.channel().close();
          channel.close();
        }
      });
    } else {
      synchronized (requestList) {
        if (isConnect) {
          cf.channel().writeAndFlush(msg);
        } else {
          requestList.add(msg);
        }
      }
    }
  }

  private HttpProxyInterceptPipeline buildPiepeline() {
    HttpProxyInterceptPipeline interceptPipeline = new HttpProxyInterceptPipeline(
        new HttpProxyIntercept() {
          @Override
          public void beforeRequest(Channel clientChannel, HttpRequest httpRequest,ProtoUtil.RequestProto requestProto,
              HttpProxyInterceptPipeline pipeline) throws Exception {
            handleProxyData(clientChannel, httpRequest, true);
          }

          @Override
          public void beforeRequest(Channel clientChannel, HttpContent httpContent,
                                    RequestProto requestProto, HttpProxyInterceptPipeline pipeline) throws Exception {
            handleProxyData(clientChannel, httpContent, true);
          }

          @Override
          public void afterResponse(Channel clientChannel, Channel proxyChannel,
              HttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) throws Exception {
            clientChannel.writeAndFlush(httpResponse);
            if (HttpHeaderValues.WEBSOCKET.toString()
                .equals(httpResponse.headers().get(HttpHeaderNames.UPGRADE))) {
              proxyChannel.pipeline().remove("httpCodec");
              clientChannel.pipeline().remove("httpCodec");
            }
          }

          @Override
          public void afterResponse(Channel clientChannel, Channel proxyChannel,
              HttpContent httpContent, HttpProxyInterceptPipeline pipeline) throws Exception {
            clientChannel.writeAndFlush(httpContent);
          }
        });
    interceptInitializer.init(interceptPipeline);
    return interceptPipeline;
  }
}
