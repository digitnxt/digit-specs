package com.digit.boundary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service configuration, mirroring the Go {@code internal/config.Config} structure and defaults.
 * Values are bound from {@code application.properties} (which in turn reads the same environment variables
 * the Go service used).
 */
@ConfigurationProperties(prefix = "boundary")
public class BoundaryProperties {

    private Server server = new Server();
    private Api api = new Api();
    private Otel otel = new Otel();
    private Logging logging = new Logging();
    private PubSub pubsub = new PubSub();
    private CacheCfg cache = new CacheCfg();

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }
    public CacheCfg getCache() { return cache; }
    public void setCache(CacheCfg cache) { this.cache = cache; }
    public Otel getOtel() { return otel; }
    public void setOtel(Otel otel) { this.otel = otel; }
    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }
    public PubSub getPubsub() { return pubsub; }
    public void setPubsub(PubSub pubsub) { this.pubsub = pubsub; }

    public static class Server {
        private String contextPath = "/boundary";
        public String getContextPath() { return contextPath; }
        public void setContextPath(String contextPath) { this.contextPath = contextPath; }
    }

    /**
     * API configuration. {@code canonical-prefix} is the path segment the canonical controller is
     * mounted under: legacy endpoints stay at {contextPath}/v3/{apiPath} (X-* header metadata),
     * canonical endpoints live at {contextPath}/v3/{canonicalPrefix}/{apiPath} (RequestMetadata in
     * the body). Both controllers are always active; the distinct prefix keeps their endpoint
     * mappings from clashing.
     */
    public static class Api {
        private String canonicalPrefix = "canonical";
        public String getCanonicalPrefix() { return canonicalPrefix; }
        public void setCanonicalPrefix(String canonicalPrefix) { this.canonicalPrefix = canonicalPrefix; }
    }

    public static class Otel {
        private String serviceName = "boundary-service";
        private String serviceVersion = "1.0.0";
        private String otlpEndpoint = "localhost:4320";
        private double samplingRatio = 1.0;
        private boolean enabled = false;
        private boolean metricsEnabled = false;
        private String prometheusPort = "9090";
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getServiceVersion() { return serviceVersion; }
        public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
        public String getOtlpEndpoint() { return otlpEndpoint; }
        public void setOtlpEndpoint(String otlpEndpoint) { this.otlpEndpoint = otlpEndpoint; }
        public double getSamplingRatio() { return samplingRatio; }
        public void setSamplingRatio(double samplingRatio) { this.samplingRatio = samplingRatio; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isMetricsEnabled() { return metricsEnabled; }
        public void setMetricsEnabled(boolean metricsEnabled) { this.metricsEnabled = metricsEnabled; }
        public String getPrometheusPort() { return prometheusPort; }
        public void setPrometheusPort(String prometheusPort) { this.prometheusPort = prometheusPort; }
    }

    public static class Logging {
        private String level = "info";
        private boolean consoleLogsEnabled = true;
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public boolean isConsoleLogsEnabled() { return consoleLogsEnabled; }
        public void setConsoleLogsEnabled(boolean consoleLogsEnabled) { this.consoleLogsEnabled = consoleLogsEnabled; }
    }

    public static class PubSub {
        private boolean enabled = true;
        private String type = "kafka";
        private Topics topics = new Topics();
        private Kafka kafka = new Kafka();
        private Redis redis = new Redis();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Topics getTopics() { return topics; }
        public void setTopics(Topics topics) { this.topics = topics; }
        public Kafka getKafka() { return kafka; }
        public void setKafka(Kafka kafka) { this.kafka = kafka; }
        public Redis getRedis() { return redis; }
        public void setRedis(Redis redis) { this.redis = redis; }
    }

    public static class Topics {
        private String createBoundary = "create-boundary-entity";
        private String updateBoundary = "update-boundary-entity";
        private String createHierarchy = "create-boundary-hierarchy";
        private String updateHierarchy = "update-boundary-hierarchy";
        private String createRelationship = "create-boundary-relationship";
        private String updateRelationship = "update-boundary-relationship";
        public String getCreateBoundary() { return createBoundary; }
        public void setCreateBoundary(String v) { this.createBoundary = v; }
        public String getUpdateBoundary() { return updateBoundary; }
        public void setUpdateBoundary(String v) { this.updateBoundary = v; }
        public String getCreateHierarchy() { return createHierarchy; }
        public void setCreateHierarchy(String v) { this.createHierarchy = v; }
        public String getUpdateHierarchy() { return updateHierarchy; }
        public void setUpdateHierarchy(String v) { this.updateHierarchy = v; }
        public String getCreateRelationship() { return createRelationship; }
        public void setCreateRelationship(String v) { this.createRelationship = v; }
        public String getUpdateRelationship() { return updateRelationship; }
        public void setUpdateRelationship(String v) { this.updateRelationship = v; }
    }

    public static class Kafka {
        private String brokers = "localhost:9092";
        private boolean autoCreate = true;
        private int partitions = 1;
        private int replication = 1;
        private String consumerGroup = "boundary-service";
        public String getBrokers() { return brokers; }
        public void setBrokers(String brokers) { this.brokers = brokers; }
        public boolean isAutoCreate() { return autoCreate; }
        public void setAutoCreate(boolean autoCreate) { this.autoCreate = autoCreate; }
        public int getPartitions() { return partitions; }
        public void setPartitions(int partitions) { this.partitions = partitions; }
        public int getReplication() { return replication; }
        public void setReplication(int replication) { this.replication = replication; }
        public String getConsumerGroup() { return consumerGroup; }
        public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
    }

    /**
     * Cache configuration, mirroring Go {@code config.CacheConfig} (CACHE_TYPE + CACHE_REDIS_*).
     * {@code type} defaults to {@code redis} (the Go default); any other value selects in-memory.
     */
    public static class CacheCfg {
        private String type = "redis";
        private CacheRedis redis = new CacheRedis();
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public CacheRedis getRedis() { return redis; }
        public void setRedis(CacheRedis redis) { this.redis = redis; }
    }

    public static class CacheRedis {
        private String address = "localhost:6379";
        private String password = "";
        private int db = 0;
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDb() { return db; }
        public void setDb(int db) { this.db = db; }
    }

    public static class Redis {
        private String address = "localhost:6379";
        private String password = "";
        private int db = 0;
        private String consumerGroup = "boundary-service";
        private String consumerId = "boundary-service-1";
        private int retentionDays = 7;
        private long maxStreamLength = 1_000_000L;
        private long cleanupIntervalSeconds = 86400L;
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDb() { return db; }
        public void setDb(int db) { this.db = db; }
        public String getConsumerGroup() { return consumerGroup; }
        public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
        public String getConsumerId() { return consumerId; }
        public void setConsumerId(String consumerId) { this.consumerId = consumerId; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public long getMaxStreamLength() { return maxStreamLength; }
        public void setMaxStreamLength(long maxStreamLength) { this.maxStreamLength = maxStreamLength; }
        public long getCleanupIntervalSeconds() { return cleanupIntervalSeconds; }
        public void setCleanupIntervalSeconds(long cleanupIntervalSeconds) { this.cleanupIntervalSeconds = cleanupIntervalSeconds; }
    }
}
