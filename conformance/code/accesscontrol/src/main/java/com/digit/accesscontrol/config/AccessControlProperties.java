package com.digit.accesscontrol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Service configuration, mirroring the Go {@code internal/config.Config} structure and defaults.
 * Values are bound from {@code application.yml} (which in turn reads the same environment variables
 * the Go service used: SERVER_CONTEXT_PATH default /access, etc.).
 */
@ConfigurationProperties(prefix = "accesscontrol")
public class AccessControlProperties {

    private Server server = new Server();
    private Otel otel = new Otel();
    private Logging logging = new Logging();
    @NestedConfigurationProperty
    private TenantMigration tenantMigration = new TenantMigration();
    private PubSub pubsub = new PubSub();

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Otel getOtel() { return otel; }
    public void setOtel(Otel otel) { this.otel = otel; }
    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }
    public TenantMigration getTenantMigration() { return tenantMigration; }
    public void setTenantMigration(TenantMigration tenantMigration) { this.tenantMigration = tenantMigration; }
    public PubSub getPubsub() { return pubsub; }
    public void setPubsub(PubSub pubsub) { this.pubsub = pubsub; }

    public static class Server {
        private String contextPath = "/access";
        public String getContextPath() { return contextPath; }
        public void setContextPath(String contextPath) { this.contextPath = contextPath; }
    }

    public static class Otel {
        private String serviceName = "accesscontrol-service";
        private String serviceVersion = "1.0.0";
        private String otlpEndpoint = "localhost:4320";
        private double samplingRatio = 1.0;
        private boolean enabled = true;
        private boolean metricsEnabled = true;
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

    public static class TenantMigration {
        private boolean enabled = false;
        private String topic = "account-migration";
        private String flywayLocations = "classpath:db/migration";
        private String schemaTable = "accesscontrol_schema";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getFlywayLocations() { return flywayLocations; }
        public void setFlywayLocations(String flywayLocations) { this.flywayLocations = flywayLocations; }
        public String getSchemaTable() { return schemaTable; }
        public void setSchemaTable(String schemaTable) { this.schemaTable = schemaTable; }
    }

    public static class PubSub {
        private boolean enabled = true;
        private String type = "kafka";
        private Kafka kafka = new Kafka();
        private Redis redis = new Redis();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Kafka getKafka() { return kafka; }
        public void setKafka(Kafka kafka) { this.kafka = kafka; }
        public Redis getRedis() { return redis; }
        public void setRedis(Redis redis) { this.redis = redis; }
    }

    public static class Kafka {
        private String brokers = "127.0.0.1:9092";
        private boolean autoCreate = true;
        private int partitions = 1;
        private int replication = 1;
        private String consumerGroup = "accesscontrol-service";
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

    public static class Redis {
        private String address = "localhost:6379";
        private String password = "";
        private int db = 0;
        private String consumerGroup = "accesscontrol-service";
        private String consumerId = "accesscontrol-service-1";
        private int retentionDays = 7;
        private long maxStreamLength = 1_000_000L;
        private long cleanupIntervalSeconds = 3600L;
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
