package com.digit.employee.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Service configuration, mirroring the Go {@code internal/config.Config} structure and defaults.
 * Values are bound from {@code application.yml} (which in turn reads the same environment variables
 * the Go service used).
 */
@ConfigurationProperties(prefix = "employee")
public class EmployeeProperties {

    private Server server = new Server();
    private Otel otel = new Otel();
    private Logging logging = new Logging();
    private IdGen idgen = new IdGen();
    private Boundary boundary = new Boundary();
    private Individual individual = new Individual();
    private Keycloak keycloak = new Keycloak();
    @NestedConfigurationProperty
    private TenantMigration tenantMigration = new TenantMigration();
    private PubSub pubsub = new PubSub();

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Otel getOtel() { return otel; }
    public void setOtel(Otel otel) { this.otel = otel; }
    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }
    public IdGen getIdgen() { return idgen; }
    public void setIdgen(IdGen idgen) { this.idgen = idgen; }
    public Boundary getBoundary() { return boundary; }
    public void setBoundary(Boundary boundary) { this.boundary = boundary; }
    public Individual getIndividual() { return individual; }
    public void setIndividual(Individual individual) { this.individual = individual; }
    public Keycloak getKeycloak() { return keycloak; }
    public void setKeycloak(Keycloak keycloak) { this.keycloak = keycloak; }
    public TenantMigration getTenantMigration() { return tenantMigration; }
    public void setTenantMigration(TenantMigration tenantMigration) { this.tenantMigration = tenantMigration; }
    public PubSub getPubsub() { return pubsub; }
    public void setPubsub(PubSub pubsub) { this.pubsub = pubsub; }

    public static class Server {
        private String contextPath = "/employee";
        public String getContextPath() { return contextPath; }
        public void setContextPath(String contextPath) { this.contextPath = contextPath; }
    }

    public static class Otel {
        private String serviceName = "employee-service";
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

    public static class IdGen {
        private String host = "http://localhost:8100";
        private String path = "/idgen/v3/generate";
        private String idgenName = "employee.idgen";
        private boolean enabled = true;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getIdgenName() { return idgenName; }
        public void setIdgenName(String idgenName) { this.idgenName = idgenName; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Boundary {
        private String baseUrl = "http://localhost:8095";
        // Full relationship endpoint path (Go BoundaryConfig.Path). Config-driven so deployments can
        // re-route/version the endpoint without a code change. Boundary validation is unconditional
        // (Go-exact) — there is no enabled flag.
        private String path = "/boundary/v3/relationship";
        private boolean enabled = true;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Individual {
        private String host = "http://localhost:8086";
        // Full path to the individuals collection (Go IndividualConfig.Path); the client appends
        // "/{individualId}". Validation is unconditional (Go-exact) — no enabled flag.
        private String path = "/individuals/v3/individuals";
        private boolean enabled = true;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Keycloak {
        private String baseUrl = "https://digit-lts.digit.org/keycloak";
        private boolean enabled = true;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class TenantMigration {
        private boolean enabled = false;
        private String topic = "account-migration";
        private String flywayLocations = "classpath:db/migration";
        private String schemaTable = "employee_schema";
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
        private String createEmployee = "employee-create-employee";
        private String updateEmployee = "employee-update-employee";
        private String deleteEmployee = "employee-delete-employee";
        private String createJurisdiction = "employee-create-jurisdiction";
        private String updateJurisdiction = "employee-update-jurisdiction";
        public String getCreateEmployee() { return createEmployee; }
        public void setCreateEmployee(String createEmployee) { this.createEmployee = createEmployee; }
        public String getUpdateEmployee() { return updateEmployee; }
        public void setUpdateEmployee(String updateEmployee) { this.updateEmployee = updateEmployee; }
        public String getDeleteEmployee() { return deleteEmployee; }
        public void setDeleteEmployee(String deleteEmployee) { this.deleteEmployee = deleteEmployee; }
        public String getCreateJurisdiction() { return createJurisdiction; }
        public void setCreateJurisdiction(String createJurisdiction) { this.createJurisdiction = createJurisdiction; }
        public String getUpdateJurisdiction() { return updateJurisdiction; }
        public void setUpdateJurisdiction(String updateJurisdiction) { this.updateJurisdiction = updateJurisdiction; }
    }

    public static class Kafka {
        private String brokers = "localhost:9092";
        private boolean autoCreate = true;
        private int partitions = 1;
        private int replication = 1;
        private String consumerGroup = "employee-service";
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
        private String consumerGroup = "employee-service";
        private String consumerId = "employee-service-1";
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
