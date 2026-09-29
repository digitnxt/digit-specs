package com.digit.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Service configuration, mirroring the Go {@code internal/config.Config} structure and defaults.
 * Values are bound from {@code application.yml} (which reads the same env vars the Go service used).
 */
@ConfigurationProperties(prefix = "account")
public class AccountProperties {

    private Server server = new Server();
    private Otel otel = new Otel();
    private Logging logging = new Logging();
    @NestedConfigurationProperty
    private Keycloak keycloak = new Keycloak();
    private Otp otp = new Otp();
    private Notification notification = new Notification();
    private Client client = new Client();
    private Auth auth = new Auth();
    private Signup signup = new Signup();
    private PubSub pubsub = new PubSub();

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Otel getOtel() { return otel; }
    public void setOtel(Otel otel) { this.otel = otel; }
    public Logging getLogging() { return logging; }
    public void setLogging(Logging logging) { this.logging = logging; }
    public Keycloak getKeycloak() { return keycloak; }
    public void setKeycloak(Keycloak keycloak) { this.keycloak = keycloak; }
    public Otp getOtp() { return otp; }
    public void setOtp(Otp otp) { this.otp = otp; }
    public Notification getNotification() { return notification; }
    public void setNotification(Notification notification) { this.notification = notification; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
    public Signup getSignup() { return signup; }
    public void setSignup(Signup signup) { this.signup = signup; }
    public PubSub getPubsub() { return pubsub; }
    public void setPubsub(PubSub pubsub) { this.pubsub = pubsub; }

    public static class Server {
        private String logLevel = "info";
        private String canonicalApiPrefix = "canonical";
        public String getLogLevel() { return logLevel; }
        public void setLogLevel(String logLevel) { this.logLevel = logLevel; }
        public String getCanonicalApiPrefix() { return canonicalApiPrefix; }
        public void setCanonicalApiPrefix(String canonicalApiPrefix) { this.canonicalApiPrefix = canonicalApiPrefix; }
    }

    public static class Otel {
        private String serviceName = "account-service";
        private String serviceVersion = "1.0.0";
        private String otlpEndpoint = "http://localhost:4317";
        private double samplingRatio = 1.0;
        private boolean enabled = false;
        private boolean metricsEnabled = false;
        private String prometheusPort = "8888";
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
        private boolean consoleLogsEnabled = true;
        public boolean isConsoleLogsEnabled() { return consoleLogsEnabled; }
        public void setConsoleLogsEnabled(boolean consoleLogsEnabled) { this.consoleLogsEnabled = consoleLogsEnabled; }
    }


    public static class Keycloak {
        private String baseUrl = "http://keycloak:8080/keycloak";
        // The browser-reachable Keycloak URL, used only for links sent to people. baseUrl above is
        // the in-cluster address the service calls the admin API on and is useless in an email, so
        // this must be overridden per environment or admins receive a link they cannot open.
        private String publicBaseUrl = "http://keycloak:8080/keycloak";
        private String adminUser = "admin";
        private String adminPass = "admin";
        private String realmConfigPath = "";
        private String citizenBrokerClientId = "citizen-broker";
        private String citizenBrokerClientSecret = "zjlrLxJCFpsRIemN9pJpUC9Wy9gjWS7m";
        private String authServerClientSecret = "changeme";
        private String employeeIamClientSecret = "changeme";
        private Tokens tokens = new Tokens();
        private Smtp smtp = new Smtp();
        public Tokens getTokens() { return tokens; }
        public void setTokens(Tokens tokens) { this.tokens = tokens; }
        public Smtp getSmtp() { return smtp; }
        public void setSmtp(Smtp smtp) { this.smtp = smtp; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String v) { this.publicBaseUrl = v; }
        public String getAdminUser() { return adminUser; }
        public void setAdminUser(String adminUser) { this.adminUser = adminUser; }
        public String getAdminPass() { return adminPass; }
        public void setAdminPass(String adminPass) { this.adminPass = adminPass; }
        public String getRealmConfigPath() { return realmConfigPath; }
        public void setRealmConfigPath(String realmConfigPath) { this.realmConfigPath = realmConfigPath; }
        public String getCitizenBrokerClientId() { return citizenBrokerClientId; }
        public void setCitizenBrokerClientId(String v) { this.citizenBrokerClientId = v; }
        public String getCitizenBrokerClientSecret() { return citizenBrokerClientSecret; }
        public void setCitizenBrokerClientSecret(String v) { this.citizenBrokerClientSecret = v; }
        public String getAuthServerClientSecret() { return authServerClientSecret; }
        public void setAuthServerClientSecret(String v) { this.authServerClientSecret = v; }
        public String getEmployeeIamClientSecret() { return employeeIamClientSecret; }
        public void setEmployeeIamClientSecret(String v) { this.employeeIamClientSecret = v; }
    }

    /**
     * SMTP server stamped onto every new realm, so the realm can send its own mail.
     *
     * <p>Needed because forgot-password is enabled: Keycloak sends that mail itself from the realm's
     * own SMTP settings rather than going through the notification service, so a realm with an empty
     * smtpServer offers a reset link that can never arrive. The two settings are a pair — enabling
     * one without the other is a dead flow, which is why {@code host} being unset is warned about.
     *
     * <p>Every value Keycloak stores in smtpServer is a string, including port and the boolean
     * toggles. They are written as such rather than as JSON numbers/booleans, which Keycloak rejects.
     *
     * <p>Applied to the parsed template, not substituted into it, so the whole block can be omitted
     * when no host is configured — a partially filled smtpServer is worse than an empty one, because
     * Keycloak will accept it and then fail at send time.
     */
    public static class Smtp {
        private String host = "";
        private String port = "587";
        private String from = "";
        private String fromDisplayName = "";
        // Blank is meaningful for these three: Keycloak treats an empty replyTo as "use from", which
        // is the behaviour wanted here, and an empty envelopeFrom leaves the SMTP envelope to the
        // relay. They are configurable only so an environment that needs them is not blocked.
        private String replyTo = "";
        private String replyToDisplayName = "";
        private String envelopeFrom = "";
        // 587 is STARTTLS, not implicit SSL. Setting both is the common misconfiguration: ssl=true on
        // 587 makes Keycloak open a TLS socket against a plaintext port and every send times out.
        private boolean ssl = false;
        private boolean starttls = true;
        private boolean auth = true;
        private String user = "";
        private String password = "";
        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public String getPort() { return port; }
        public void setPort(String v) { this.port = v; }
        public String getFrom() { return from; }
        public void setFrom(String v) { this.from = v; }
        public String getFromDisplayName() { return fromDisplayName; }
        public void setFromDisplayName(String v) { this.fromDisplayName = v; }
        public String getReplyTo() { return replyTo; }
        public void setReplyTo(String v) { this.replyTo = v; }
        public String getReplyToDisplayName() { return replyToDisplayName; }
        public void setReplyToDisplayName(String v) { this.replyToDisplayName = v; }
        public String getEnvelopeFrom() { return envelopeFrom; }
        public void setEnvelopeFrom(String v) { this.envelopeFrom = v; }
        public boolean isSsl() { return ssl; }
        public void setSsl(boolean v) { this.ssl = v; }
        public boolean isStarttls() { return starttls; }
        public void setStarttls(boolean v) { this.starttls = v; }
        public boolean isAuth() { return auth; }
        public void setAuth(boolean v) { this.auth = v; }
        public String getUser() { return user; }
        public void setUser(String v) { this.user = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
    }

    /**
     * Token and session lifespans stamped onto every new realm, in seconds.
     *
     * <p>Defaults match the values realm_config.json already carried, so an unconfigured deployment
     * provisions exactly the realm it did before. They are applied after the template is parsed
     * rather than substituted into it, because these are JSON numbers: a {@code {{.Placeholder}}} in
     * a numeric slot leaves the file invalid JSON on disk, which breaks editors and any tooling that
     * reads the template without going through this service.
     *
     * <p>Changing these affects new realms only — Keycloak stores them per realm at import, so
     * already-provisioned tenants keep whatever they were created with.
     */
    public static class Tokens {
        // Idle timeout is deliberately longer than the access-token lifespan. The other way round
        // leaves a window where the token still validates offline (Kong checks only exp against
        // JWKS) while the server-side session is already gone, so the same token is accepted by the
        // gateway and rejected by /userinfo, refresh, and introspection.
        private int accessTokenLifespan = 14400;
        private int ssoSessionIdleTimeout = 28800;
        private int ssoSessionMaxLifespan = 36000;
        private int offlineSessionIdleTimeout = 2592000;
        private int offlineSessionMaxLifespan = 5184000;
        public int getAccessTokenLifespan() { return accessTokenLifespan; }
        public void setAccessTokenLifespan(int v) { this.accessTokenLifespan = v; }
        public int getSsoSessionIdleTimeout() { return ssoSessionIdleTimeout; }
        public void setSsoSessionIdleTimeout(int v) { this.ssoSessionIdleTimeout = v; }
        public int getSsoSessionMaxLifespan() { return ssoSessionMaxLifespan; }
        public void setSsoSessionMaxLifespan(int v) { this.ssoSessionMaxLifespan = v; }
        public int getOfflineSessionIdleTimeout() { return offlineSessionIdleTimeout; }
        public void setOfflineSessionIdleTimeout(int v) { this.offlineSessionIdleTimeout = v; }
        public int getOfflineSessionMaxLifespan() { return offlineSessionMaxLifespan; }
        public void setOfflineSessionMaxLifespan(int v) { this.offlineSessionMaxLifespan = v; }
    }

    public static class Otp {
        // Internal service convention: base URL is host+port only with a trailing slash, and each
        // endpoint path carries the context path with no leading slash. Both halves are config-driven
        // so deployments can re-route or version an endpoint without a code change.
        private String baseUrl = "http://localhost:8107/";
        private String generatePath = "otp/v3/generate";
        private String verifyPath = "otp/v3/verify";
        private String resendPath = "otp/v3/resend";
        private String configPath = "otp/v3/config";
        // Purposes seeded for a new tenant. The OTP service resolves config strictly by
        // (tenantId, purpose) with no fallback, so a realm whose login flow asks for an OTP is
        // unusable until a row exists. Config-driven rather than hardcoded: the realm flows that
        // need these are themselves config, so a new flow should not require an account release.
        private List<String> bootstrapPurposes = new ArrayList<>(List.of("login", "registration"));
        private int timeoutSeconds = 10;
        private String platformTenant = "default";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getGeneratePath() { return generatePath; }
        public void setGeneratePath(String generatePath) { this.generatePath = generatePath; }
        public String getVerifyPath() { return verifyPath; }
        public void setVerifyPath(String verifyPath) { this.verifyPath = verifyPath; }
        public String getResendPath() { return resendPath; }
        public void setResendPath(String resendPath) { this.resendPath = resendPath; }
        public String getConfigPath() { return configPath; }
        public void setConfigPath(String configPath) { this.configPath = configPath; }
        public List<String> getBootstrapPurposes() { return bootstrapPurposes; }
        public void setBootstrapPurposes(List<String> v) { this.bootstrapPurposes = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getPlatformTenant() { return platformTenant; }
        public void setPlatformTenant(String platformTenant) { this.platformTenant = platformTenant; }
    }

    public static class Notification {
        // Same internal-service convention as Otp above: host+port with a trailing slash, paths
        // carrying the context path with no leading slash.
        private String baseUrl = "http://localhost:8080/";
        private String notifySendPath = "notify/v3/notifications";
        // The tenant that owns the temp-password template. Account provisions tenants, so at the
        // moment a tenant is created there is no tenant-scoped template to render from — the
        // template lives once under a platform tenant instead of being copied per tenant. Same
        // reasoning as Otp.platformTenant, and the same value, so account's two platform-tenant
        // settings agree. Case matters: notify matches tenant_id exactly, so "default" and
        // "DEFAULT" are different tenants and only the one the config was created under resolves.
        private String platformTenant = "default";
        private String tempPasswordTemplateCode = "account-tenant-temp-password";
        // The sign-in links put in the temp-password email, in the order they should appear. Whole
        // URLs rather than paths appended to a base, because the realm sits mid-path
        // (/admin/{realm}/console) and because a destination need not be Keycloak at all — pointing
        // one at a tenant-scoped app UI is a config change this way. {realm} and {tenantCode} both
        // substitute the tenant's code, in every entry.
        //
        // A list because a tenant admin usually has more than one place to go (admin console,
        // employee portal, citizen portal). Order is preserved and meaningful: the first entry is
        // treated as the primary link, and it is the one an email template written against the
        // single-URL payload still receives.
        private List<String> firstLoginUrls = new ArrayList<>();
        private int timeoutSeconds = 10;
        private boolean enabled = true;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getNotifySendPath() { return notifySendPath; }
        public void setNotifySendPath(String notifySendPath) { this.notifySendPath = notifySendPath; }
        public String getPlatformTenant() { return platformTenant; }
        public void setPlatformTenant(String platformTenant) { this.platformTenant = platformTenant; }
        public String getTempPasswordTemplateCode() { return tempPasswordTemplateCode; }
        public void setTempPasswordTemplateCode(String v) { this.tempPasswordTemplateCode = v; }
        public List<String> getFirstLoginUrls() { return firstLoginUrls; }
        public void setFirstLoginUrls(List<String> v) {
            this.firstLoginUrls = v == null ? new ArrayList<>() : v;
        }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Front-end clients created in every tenant realm (employee, citizen). One template serves both:
     * {@code {realm}} (or {@code {tenantCode}}) is replaced with the tenant's code, so a realm gets
     * redirect URIs scoped to itself rather than the whole host.
     */
    public static class Client {
        private String redirectUrl = "https://test-lts.digit.org/{realm}/*";
        public String getRedirectUrl() { return redirectUrl; }
        public void setRedirectUrl(String v) { this.redirectUrl = v; }
    }

    /**
     * Platform-operator authentication for the tenant-lifecycle endpoints. These cannot be protected
     * by the gateway's keycloak-rbac like every other service, because they run before the tenant
     * realm that would authorize them exists — so the platform realm is the authority instead.
     */
    public static class Auth {
        // Default on: off means unauthenticated tenant create/update/delete, which is the state this
        // exists to end. A missing env var must not silently reopen it.
        private boolean enabled = true;
        // The realm that vouches for platform operators. Not "the realm being acted on".
        private String platformRealm = "master";
        // Expected iss claim. Empty derives it from keycloak.public-base-url, which is what tokens
        // actually carry — keycloak.base-url is the in-cluster address and never appears in an iss.
        private String issuer = "";
        // Where signing keys are fetched. Empty derives it from keycloak.base-url, deliberately the
        // internal address: verification must not depend on egress to the public hostname.
        private String jwksUri = "";
        private String requiredRole = "SUPERADMIN";
        private int jwksCacheSeconds = 3600;
        private int clockSkewSeconds = 30;
        // "METHOD:/servlet/path" with * matching exactly one path segment. Adding an endpoint to the
        // lock is a config change, not a code change. Paths are matched after the canonical API
        // prefix is stripped, so one entry covers both /v3/... and /v3/<prefix>/... .
        private List<String> protectedEndpoints = new ArrayList<>(List.of(
                "POST:/v3/tenants",
                "PUT:/v3/tenants/*",
                "DELETE:/v3/tenants/*"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPlatformRealm() { return platformRealm; }
        public void setPlatformRealm(String v) { this.platformRealm = v; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getJwksUri() { return jwksUri; }
        public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }
        public String getRequiredRole() { return requiredRole; }
        public void setRequiredRole(String requiredRole) { this.requiredRole = requiredRole; }
        public int getJwksCacheSeconds() { return jwksCacheSeconds; }
        public void setJwksCacheSeconds(int v) { this.jwksCacheSeconds = v; }
        public int getClockSkewSeconds() { return clockSkewSeconds; }
        public void setClockSkewSeconds(int v) { this.clockSkewSeconds = v; }
        public List<String> getProtectedEndpoints() { return protectedEndpoints; }
        public void setProtectedEndpoints(List<String> v) { this.protectedEndpoints = v; }
    }

    /**
     * Self-service registration. Its own flag because it is a product decision, not a security one —
     * but note the two interact: registrations/verify creates a tenant through the same code path as
     * POST /tenants, so leaving this on means tenant creation is reachable without a platform token.
     */
    public static class Signup {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class PubSub {
        private boolean enabled = true;
        private Topics topics = new Topics();
        private Redis redis = new Redis();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Topics getTopics() { return topics; }
        public void setTopics(Topics topics) { this.topics = topics; }
        public Redis getRedis() { return redis; }
        public void setRedis(Redis redis) { this.redis = redis; }
    }

    public static class Topics {
        private String createTenant = "account-create-tenant";
        private String updateTenant = "account-update-tenant";
        private String deleteTenant = "account-delete-tenant";
        private String createTenantConfig = "account-create-tenant-config";
        private String updateTenantConfig = "account-update-tenant-config";
        private String migrationTopic = "account-migration";
        public String getCreateTenant() { return createTenant; }
        public void setCreateTenant(String v) { this.createTenant = v; }
        public String getUpdateTenant() { return updateTenant; }
        public void setUpdateTenant(String v) { this.updateTenant = v; }
        public String getDeleteTenant() { return deleteTenant; }
        public void setDeleteTenant(String v) { this.deleteTenant = v; }
        public String getCreateTenantConfig() { return createTenantConfig; }
        public void setCreateTenantConfig(String v) { this.createTenantConfig = v; }
        public String getUpdateTenantConfig() { return updateTenantConfig; }
        public void setUpdateTenantConfig(String v) { this.updateTenantConfig = v; }
        public String getMigrationTopic() { return migrationTopic; }
        public void setMigrationTopic(String v) { this.migrationTopic = v; }
    }


    public static class Redis {
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
}
