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
    public PubSub getPubsub() { return pubsub; }
    public void setPubsub(PubSub pubsub) { this.pubsub = pubsub; }

    public static class Server {
        private String canonicalApiPrefix = "canonical";
        public String getCanonicalApiPrefix() { return canonicalApiPrefix; }
        public void setCanonicalApiPrefix(String canonicalApiPrefix) { this.canonicalApiPrefix = canonicalApiPrefix; }
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
        // Internal service convention: the host carries the trailing slash and the path carries none,
        // so the clients can concatenate the two directly.
        private String host = "http://localhost:8100/";
        private String path = "idgen/v3/generate";
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
        private String baseUrl = "http://localhost:8095/";
        // Full relationship endpoint path (Go BoundaryConfig.Path). Config-driven so deployments can
        // re-route/version the endpoint without a code change. Boundary validation is unconditional
        // (Go-exact) — there is no enabled flag.
        private String path = "boundary/v3/relationship";
        private boolean enabled = true;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Individual {
        private String host = "http://localhost:8086/";
        // Full path to the individuals collection (Go IndividualConfig.Path); the client appends
        // "/{individualId}". Validation is unconditional (Go-exact) — no enabled flag.
        private String path = "individuals/v3/individuals";
        private boolean enabled = true;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * clientId / clientSecret are the service-account (client_credentials) credentials used for
     * read-only realm lookups — role members, role existence, user existence — which an ordinary
     * caller's token is not permitted to make. Leave them empty to keep forwarding the caller's
     * token instead. No default for the secret: a baked-in value would be a credential in source.
     *
     * <p>The token is issued by the tenant's own realm, so the client must exist in EVERY tenant
     * realm under this id and secret — it is provisioned as part of the realm config applied at
     * realm creation. A tenant whose realm lacks the client falls back to the caller's token and
     * 403s as before. Mirrors Go config.KeycloakConfig.
     */
    public static class Keycloak {
        private String baseUrl = "https://digit-lts.digit.org/keycloak";
        private boolean enabled = true;
        private String clientId = "";
        private String clientSecret = "";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
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


}
