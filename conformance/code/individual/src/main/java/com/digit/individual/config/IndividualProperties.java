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
    public PubSub getPubsub() { return pubsub; }
    public void setPubsub(PubSub pubsub) { this.pubsub = pubsub; }

    public static class Server {
        private String canonicalApiPrefix = "canonical";
        public String getCanonicalApiPrefix() { return canonicalApiPrefix; }
        public void setCanonicalApiPrefix(String canonicalApiPrefix) { this.canonicalApiPrefix = canonicalApiPrefix; }
    }

    public static class Idgen {
        // Internal service convention: the host carries the trailing slash and the path carries none,
        // so the client can concatenate the two directly.
        private String host = "http://idgen:8080/";
        private String path = "idgen/v3/generate";
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


    public static class PubSub {
        private boolean enabled = true;
        private Topics topics = new Topics();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Topics getTopics() { return topics; }
        public void setTopics(Topics topics) { this.topics = topics; }
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


}
