package lee.study.proxyee.pojo;

/**
 * @author LinGH
 * @date 2018-04-24
 */
public class GlobalProxyConfig {

    private boolean record;
    private MongoDb mongodb;
    private IgnoreConfig ignore;

    public MongoDb getMongodb() {
        return mongodb;
    }

    public boolean isRecord() {
        return record;
    }

    public void setRecord(boolean record) {
        this.record = record;
    }

    public void setMongodb(MongoDb mongodb) {
        this.mongodb = mongodb;
    }

    public IgnoreConfig getIgnore() {
        return ignore;
    }

    public void setIgnore(IgnoreConfig ignore) {
        this.ignore = ignore;
    }
}
