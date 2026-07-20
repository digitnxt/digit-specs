package com.digit.individual.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Service configuration. Values are bound from {@code application.yml}, which resolves them from
 * environment variables with the defaults declared there.
 */
@ConfigurationProperties(prefix = "individual")
public class IndividualProperties {

    private Server server = new Server();
    private Idgen idgen = new Idgen();
    private Vault vault = new Vault();
    private Otel otel = new Otel();
    private Logging logging = new Logging();
    @NestedConfigurationProperty
    private TenantMigration tenantMigration = new TenantMigration();
    private PubSub pubsub = new PubSub();

    /**
     * Pepper for the mobile-number blind index (HMAC-SHA256). Bound from HMAC_SECRET. No default is
     * provided so a missing secret is caught at startup (when Vault is on) rather than silently
     * weakening the hash. Empty is tolerated only when Vault is off (plaintext at rest).
     */
    private String hmacSecret = "";

    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Idgen getIdgen() { return idgen; }
    public void setIdgen(Idgen idgen) { this.idgen = idgen; }
    public Vault getVault() { return vault; }
    public void setVault(Vault vault) { this.vault = vault; }
    public Otel getOtel() { return otel; }
    public void setOtel(Otel otel) { this.otel = otel; }
    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }
    public TenantMigration getTenantMigration() { return tenantMigration; }
    public void setTenantMigration(TenantMigration tenantMigration) { this.tenantMigration = tenantMigration; }
    public PubSub getPubsub() { return pubsub; }
    public void setPubsub(PubSub pubsub) { this.pubsub = pubsub; }

    public static class Server {
        private String contextPath = "/individuals";
        public String getContextPath() { return contextPath; }
        public void setContextPath(String contextPath) { this.contextPath = contextPath; }
    }

    public static class Idgen {
        private String host = "http://idgen:8080";
        private String path = "/idgen/v3/generate";
        private boolean enabled = true;
        private String format = "individual.id";
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
    }

    public static class Vault {
        private String address = "http://localhost:8202";
        private String roleId = "";
        private String secretId = "";
        private boolean enabled = true;
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getRoleId() { return roleId; }
        public void setRoleId(String roleId) { this.roleId = roleId; }
        public String getSecretId() { return secretId; }
        public void setSecretId(String secretId) { this.secretId = secretId; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Otel {
        private String serviceName = "individual-service";
        private String serviceVersion = "1.0.0";
        private String otlpEndpoint = "http://localhost:4318";
        private double samplingRatio = 1.0;
        private boolean enabled = true;
        private boolean metricsEnabled = true;
        private String prometheusPort = "";
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
        private String schemaTable = "individual_schema";
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
        private String createIndividual = "individual-create-individual";
        private String updateIndividual = "individual-update-individual";
        private String deleteIndividual = "individual-delete-individual";
        private String upsertConfig = "individual-upsert-config";
        public String getCreateIndividual() { return createIndividual; }
        public void setCreateIndividual(String createIndividual) { this.createIndividual = createIndividual; }
        public String getUpdateIndividual() { return updateIndividual; }
        public void setUpdateIndividual(String updateIndividual) { this.updateIndividual = updateIndividual; }
        public String getDeleteIndividual() { return deleteIndividual; }
        public void setDeleteIndividual(String deleteIndividual) { this.deleteIndividual = deleteIndividual; }
        public String getUpsertConfig() { return upsertConfig; }
        public void setUpsertConfig(String upsertConfig) { this.upsertConfig = upsertConfig; }
    }

    public static class Kafka {
        private String brokers = "localhost:9092";
        private boolean autoCreate = true;
        private int partitions = 1;
        private int replication = 1;
        private String consumerGroup = "individual-service";
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
        private String consumerGroup = "individual-service";
        private String consumerId = "individual-service-1";
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
