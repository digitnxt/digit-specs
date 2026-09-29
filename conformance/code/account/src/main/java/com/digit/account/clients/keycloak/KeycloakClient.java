package com.digit.account.clients.keycloak;

import com.digit.account.config.AccountProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Keycloak admin client. Mirrors Go internal/keycloak/client.go. */
public class KeycloakClient {

    private final String baseUrl;
    private final String adminUser;
    private final String clientRedirectUrlTemplate;
    private final String adminPass;
    private final AccountProperties.Keycloak cfg;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;

    public KeycloakClient(String baseUrl, String adminUser, String adminPass,
                          AccountProperties.Keycloak cfg, String clientRedirectUrlTemplate,
                          ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.adminUser = adminUser;
        this.adminPass = adminPass;
        this.cfg = cfg;
        this.clientRedirectUrlTemplate = clientRedirectUrlTemplate;
        this.objectMapper = objectMapper;
    }

    public String getAdminToken() {
        String url = baseUrl + "/realms/master/protocol/openid-connect/token";
        String data = "username=" + adminUser + "&password=" + adminPass
                + "&grant_type=password&client_id=admin-cli";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(data))
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            // Go's GetAdminToken returns the raw transport error; the caller wraps it with
            // "failed to get admin token: %v". Carry a non-null cause so the wrap never reads ": null".
            throw new RuntimeException(cause(e), e);
        }
        Map<String, Object> result = parseMap(resp.body());
        Object token = result == null ? null : result.get("access_token");
        if (token instanceof String s) {
            return s;
        }
        throw new RuntimeException("failed to get access token: " + new String(resp.body(), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(byte[] body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Imports the realm for a tenant and wires up its citizen token exchange.
     *
     * <p>{@code mobileNumber} becomes the superuser's {@code mobileNumber} attribute. It is the
     * tenant's own contact phone, which the API declares optional, so it may be null or blank —
     * the attribute is then imported empty rather than carrying a placeholder that looks like a
     * real number but is not one anybody can be reached on.
     *
     * <p>{@code passwordTemporary} makes the superuser choose a new password at first login. It is a
     * parameter rather than a constant because it should only apply to a password this service
     * generated and emailed: forcing a reset on one the caller chose themselves would be gratuitous,
     * while leaving a generated one permanent would make the emailed secret permanent.
     */
    public void createRealmWithFullConfig(String tenantCode, String tenantEmail, String tenantName,
                                          String tenantPassword, String mobileNumber,
                                          boolean passwordTemporary) {
        String token;
        try {
            token = getAdminToken();
        } catch (RuntimeException e) {
            // Mirror Go: fmt.Errorf("failed to get admin token: %v", err)
            throw new RuntimeException("failed to get admin token: " + cause(e), e);
        }

        Map<String, String> data = new LinkedHashMap<>();
        data.put("TenantCode", tenantCode);
        data.put("TenantCodeLowerCase", tenantCode.toLowerCase(Locale.ROOT));
        data.put("TenantEmail", tenantEmail);
        data.put("TenantName", tenantName);
        data.put("TenantPassword", tenantPassword);
        data.put("MobileNumber", mobileNumber);
        // The employee and citizen clients get redirect URIs scoped to this realm. The web origin
        // is the same value without the trailing wildcard: a redirect URI matches a path pattern,
        // whereas an origin is scheme+host+port and Keycloak rejects one carrying a path glob.
        String redirectUri = clientRedirectUrlTemplate
                .replace("{realm}", tenantCode)
                .replace("{tenantCode}", tenantCode);
        data.put("ClientRedirectUri", redirectUri);
        data.put("ClientWebOrigin", stripTrailingWildcard(redirectUri));
        data.put("AuthBaseUrl", baseUrl);
        data.put("AuthAdminUrl", baseUrl);
        data.put("AuthServerClientSecret", cfg.getAuthServerClientSecret());
        data.put("EmployeeIamClientSecret", cfg.getEmployeeIamClientSecret());

        Map<String, Object> realmConfig = loadAndProcessRealmConfig(data);
        applyTokenLifespans(realmConfig);
        applySmtpServer(realmConfig);
        if (passwordTemporary) {
            requirePasswordUpdate(realmConfig, tenantEmail);
        }
        createRealmWithConfig(realmConfig, token);
        overrideClientBrowserFlow(tenantCode, "security-admin-console", "browser", token);
        overrideClientBrowserFlow(tenantCode, "citizen", "citizen mobile login", token);
        setupCitizenTokenExchange(tenantCode, token);
    }

    /**
     * A web origin is scheme://host[:port] with no path, so the trailing path wildcard that makes a
     * redirect URI useful has to come off. Keycloak treats an origin carrying a path as not matching
     * the browser's Origin header, which surfaces as an opaque CORS failure in the front end rather
     * than an error at import time.
     */
    private static String stripTrailingWildcard(String url) {
        String out = url;
        if (out.endsWith("/*")) {
            out = out.substring(0, out.length() - 2);
        } else if (out.endsWith("*")) {
            out = out.substring(0, out.length() - 1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    /**
     * Marks the tenant superuser as having to choose a new password at first login. Only called for
     * a password this service generated — one the caller supplied is theirs already and is left
     * permanent.
     *
     * <p>Applied to the parsed representation rather than templated into realm_config.json for two
     * reasons. That file has to stay valid JSON: every placeholder in it sits inside a quoted
     * string, and a bare {@code {{...}}} standing in for an array or a boolean breaks parsers and
     * editors alike. And {@code credentials[].temporary}, the field that reads like the obvious way
     * to do this, is silently dropped by realm import — verified on Keycloak 25.0.1, where a user
     * imported with {@code temporary: true} came back with empty requiredActions and a permanent
     * password. requiredActions is the only one import honours.
     *
     * <p>Throws when the user cannot be found instead of carrying on: a silent miss would hand out
     * an emailed password that never expires, which is the outcome this exists to prevent. Nothing
     * has been created in Keycloak at this point, so failing here leaves nothing to clean up.
     */
    /**
     * Overwrites the template's token and session lifespans with the configured ones.
     *
     * <p>Applied to the parsed map rather than substituted into the template text for the same
     * reason as {@link #requirePasswordUpdate}: these are JSON numbers, and a {@code {{.Placeholder}}}
     * sitting unquoted in a numeric slot leaves realm_config.json invalid on disk. Quoting it instead
     * would parse, but Keycloak rejects a string where it expects a number.
     *
     * <p>Unconditional — the defaults equal what the template already carried, so an unconfigured
     * deployment gets a byte-identical realm and there is no branch to reason about.
     *
     * <p>clientSessionIdleTimeout and clientSessionMaxLifespan are deliberately left alone. They
     * appear twice, once at the realm root as numbers and once inside "attributes" as strings, and
     * Keycloak treats the attributes copy as authoritative when both are present; making them
     * configurable means keeping the two in step, which is not worth it while both are 0 (meaning
     * "fall back to the SSO session values" that this method does control).
     */
    /**
     * Fills the realm's smtpServer from config so the realm can send its own mail.
     *
     * <p>Keycloak sends the forgot-password mail itself, from these settings, rather than through the
     * notification service — so with realm_config's {@code resetPasswordAllowed: true} and an empty
     * smtpServer, the login page offers a reset link whose mail can never be delivered. That pairing
     * is the reason this exists.
     *
     * <p>Left untouched when no host is configured, rather than written half-populated: Keycloak
     * accepts a partial smtpServer and only fails later, at send time, where the failure surfaces to
     * a user mid-reset instead of to whoever deployed it.
     *
     * <p>Every value is a string, including the port and the three toggles. Keycloak stores this map
     * as strings and rejects a JSON number or boolean here.
     */
    void applySmtpServer(Map<String, Object> realmConfig) {
        AccountProperties.Smtp s = cfg.getSmtp();
        if (s.getHost() == null || s.getHost().isBlank()) {
            return;
        }
        Map<String, Object> smtp = new LinkedHashMap<>();
        smtp.put("host", s.getHost());
        smtp.put("port", s.getPort());
        smtp.put("from", s.getFrom());
        smtp.put("fromDisplayName", s.getFromDisplayName());
        smtp.put("replyTo", s.getReplyTo());
        smtp.put("replyToDisplayName", s.getReplyToDisplayName());
        smtp.put("envelopeFrom", s.getEnvelopeFrom());
        smtp.put("ssl", String.valueOf(s.isSsl()));
        smtp.put("starttls", String.valueOf(s.isStarttls()));
        smtp.put("auth", String.valueOf(s.isAuth()));
        // Only sent when authentication is on. Keycloak keeps whatever is in these two fields even
        // with auth off, so writing them unconditionally would store a password the realm never uses.
        if (s.isAuth()) {
            smtp.put("user", s.getUser());
            smtp.put("password", s.getPassword());
        }
        realmConfig.put("smtpServer", smtp);
    }

    void applyTokenLifespans(Map<String, Object> realmConfig) {
        AccountProperties.Tokens t = cfg.getTokens();
        realmConfig.put("accessTokenLifespan", t.getAccessTokenLifespan());
        realmConfig.put("ssoSessionIdleTimeout", t.getSsoSessionIdleTimeout());
        realmConfig.put("ssoSessionMaxLifespan", t.getSsoSessionMaxLifespan());
        realmConfig.put("offlineSessionIdleTimeout", t.getOfflineSessionIdleTimeout());
        realmConfig.put("offlineSessionMaxLifespan", t.getOfflineSessionMaxLifespan());
    }

    @SuppressWarnings("unchecked")
    private static void requirePasswordUpdate(Map<String, Object> realmConfig, String username) {
        if (realmConfig.get("users") instanceof List<?> users) {
            for (Object entry : users) {
                if (!(entry instanceof Map)) {
                    continue;
                }
                Map<String, Object> user = (Map<String, Object>) entry;
                if (!username.equals(user.get("username"))) {
                    continue;
                }
                List<Object> actions = user.get("requiredActions") instanceof List<?> existing
                        ? new ArrayList<>(existing) : new ArrayList<>();
                if (!actions.contains(UPDATE_PASSWORD)) {
                    actions.add(UPDATE_PASSWORD);
                }
                user.put("requiredActions", actions);
                return;
            }
        }
        throw new RuntimeException("realm config has no user \"" + username + "\" to require a "
                + "password update for; refusing to provision a realm whose emailed password would "
                + "never have to be changed");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadAndProcessRealmConfig(Map<String, String> data) {
        byte[] templateBytes = findAndReadRealmConfig();
        String tmpl = new String(templateBytes, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : data.entrySet()) {
            tmpl = tmpl.replace("{{." + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        try {
            return objectMapper.readValue(tmpl, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse JSON: " + e.getMessage(), e);
        }
    }

    private byte[] findAndReadRealmConfig() {
        List<String> paths = new ArrayList<>();
        if (cfg.getRealmConfigPath() != null && !cfg.getRealmConfigPath().isEmpty()) {
            paths.add(cfg.getRealmConfigPath());
        }
        paths.add("internal/keycloak/realm_config.json");
        paths.add("realm_config.json");
        for (String p : paths) {
            try {
                return Files.readAllBytes(Path.of(p));
            } catch (IOException ignored) {
                // try next
            }
        }
        // Bundled classpath resource (Java packaging).
        try (var in = getClass().getClassLoader().getResourceAsStream("realm_config.json")) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
            // fall through
        }
        throw new RuntimeException("realm config file not found in any of the expected locations: " + paths);
    }

    private void createRealmWithConfig(Map<String, Object> config, String token) {
        String url = baseUrl + "/admin/realms";
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(config);
        } catch (Exception e) {
            throw new RuntimeException("failed to marshal config: " + e.getMessage(), e);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<byte[]> resp = send(req, "create realm");
        if (resp.statusCode() != 201 && resp.statusCode() != 409) {
            throw new RuntimeException("failed to create realm, status: " + resp.statusCode()
                    + ", body: " + new String(resp.body(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Binds a client's browser flow to a named flow, overriding the realm default.
     *
     * <p>Must happen after import, never in realm_config.json: import resolves
     * {@code authenticationFlowBindingOverrides} by flow **id**, and ids are minted per realm at
     * import time. An alias in the template does not merely fail to apply — it aborts the entire
     * import with "Unable to resolve auth flow binding override for: browser", so every tenant
     * creation returns 500. The template must leave the map empty and this fills it in.
     *
     * <p>{@code security-admin-console} is pointed at the built-in {@code browser} flow because the
     * realm default asks for a username and then a one-time code, which a freshly provisioned admin
     * has no way to receive; the built-in flow asks for username and password, so the password they
     * were emailed is enough to get in and change it.
     */
    private void overrideClientBrowserFlow(String tenantCode, String clientId, String flowAlias,
                                           String token) {
        String flowId = getFlowIdByAlias(tenantCode, flowAlias, token);
        String clientUuid = getClientInternalId(tenantCode, clientId, token);
        String url = baseUrl + "/admin/realms/" + tenantCode + "/clients/" + clientUuid;

        HttpRequest read = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<byte[]> resp = send(read, "read " + clientId + " client");
        if (resp.statusCode() != 200) {
            throw new RuntimeException("failed to read the " + clientId + " client, status: "
                    + resp.statusCode());
        }
        Map<String, Object> client = parseMap(resp.body());
        if (client == null) {
            throw new RuntimeException("failed to parse the " + clientId + " client");
        }
        // Replaces rather than merges: browser is the only binding this sets, and carrying an
        // unresolvable leftover back in the PUT would fail the update the same way import does.
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("browser", flowId);
        client.put("authenticationFlowBindingOverrides", overrides);
        makeRequest("PUT", url, client, token,
                "bind " + clientId + " browser flow to \"" + flowAlias + "\"");
    }

    @SuppressWarnings("unchecked")
    private String getFlowIdByAlias(String tenantCode, String alias, String token) {
        String url = baseUrl + "/admin/realms/" + tenantCode + "/authentication/flows";
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<byte[]> resp = send(req, "list authentication flows");
        if (resp.statusCode() != 200) {
            throw new RuntimeException("failed to list authentication flows, status: "
                    + resp.statusCode());
        }
        List<Map<String, Object>> flows;
        try {
            flows = objectMapper.readValue(resp.body(), List.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse authentication flows: " + e.getMessage(), e);
        }
        for (Map<String, Object> flow : flows) {
            if (alias.equals(flow.get("alias"))) {
                return String.valueOf(flow.get("id"));
            }
        }
        throw new RuntimeException("realm " + tenantCode + " has no authentication flow \"" + alias + "\"");
    }

    private void setupCitizenTokenExchange(String tenantCode, String token) {
        createCitizenIdentityProvider(tenantCode, token);
        enableIdpTokenExchange(tenantCode, token);
        attachAuthServerClientPolicy(tenantCode, token);
    }

    private void createCitizenIdentityProvider(String tenantCode, String token) {
        String url = baseUrl + "/admin/realms/" + tenantCode + "/identity-provider/instances";
        Map<String, Object> idpCfg = new LinkedHashMap<>();
        idpCfg.put("alias", "citizen");
        idpCfg.put("displayName", "Citizen Identity Provider");
        idpCfg.put("providerId", "oidc");
        idpCfg.put("enabled", true);
        idpCfg.put("updateProfileFirstLoginMode", "on");
        idpCfg.put("trustEmail", false);
        idpCfg.put("storeToken", false);
        idpCfg.put("addReadTokenRoleOnCreate", false);
        idpCfg.put("authenticateByDefault", false);
        idpCfg.put("linkOnly", false);
        idpCfg.put("firstBrokerLoginFlowAlias", "first broker login");
        Map<String, Object> config = new LinkedHashMap<>();
        // Keeps the IdP enabled — token exchange needs it — while removing its button from the
        // login page. Tenant admins sign in with their own realm credentials, so offering them a
        // "Citizen Identity Provider" route is only a way to end up in the wrong account.
        config.put("hideOnLoginPage", "true");
        config.put("clientId", cfg.getCitizenBrokerClientId());
        config.put("clientSecret", cfg.getCitizenBrokerClientSecret());
        config.put("discoveryEndpoint", baseUrl + "/realms/CITIZEN/.well-known/openid-configuration");
        config.put("useJwksUrl", "true");
        config.put("syncMode", "IMPORT");
        config.put("authorizationUrl", baseUrl + "/realms/CITIZEN/protocol/openid-connect/auth");
        config.put("tokenUrl", baseUrl + "/realms/CITIZEN/protocol/openid-connect/token");
        config.put("userInfoUrl", baseUrl + "/realms/CITIZEN/protocol/openid-connect/userinfo");
        config.put("issuer", baseUrl + "/realms/CITIZEN");
        config.put("jwksUrl", baseUrl + "/realms/CITIZEN/protocol/openid-connect/certs");
        config.put("validateSignature", "true");
        config.put("clientAuthMethod", "client_secret_post");
        config.put("pkceEnabled", "false");
        config.put("defaultScope", "openid profile email");
        idpCfg.put("config", config);
        makeRequest("POST", url, idpCfg, token, "create identity provider");
    }

    private void enableIdpTokenExchange(String tenantCode, String token) {
        String url = baseUrl + "/admin/realms/" + tenantCode
                + "/identity-provider/instances/citizen/management/permissions";
        Map<String, Object> permission = new LinkedHashMap<>();
        permission.put("enabled", true);
        makeRequest("PUT", url, permission, token, "enable identity provider permissions");
    }

    @SuppressWarnings("unchecked")
    private void attachAuthServerClientPolicy(String tenantCode, String token) {
        String clientId = getClientInternalId(tenantCode, "auth-server", token);
        String realmMgmtClientId = getClientInternalId(tenantCode, "realm-management", token);

        String policyUrl = baseUrl + "/admin/realms/" + tenantCode + "/clients/" + realmMgmtClientId
                + "/authz/resource-server/policy/client";
        Map<String, Object> policyCfg = new LinkedHashMap<>();
        policyCfg.put("name", "auth-server-token-exchange-policy");
        policyCfg.put("description", "Policy allowing auth-server to perform token exchange");
        policyCfg.put("type", "client");
        policyCfg.put("logic", "POSITIVE");
        policyCfg.put("decisionStrategy", "UNANIMOUS");
        policyCfg.put("clients", List.of(clientId));
        makeRequest("POST", policyUrl, policyCfg, token, "create auth-server client policy");

        String permissionId = getTokenExchangePermissionId(tenantCode, realmMgmtClientId, token);
        String attachUrl = baseUrl + "/admin/realms/" + tenantCode + "/clients/" + realmMgmtClientId
                + "/authz/resource-server/permission/scope/" + permissionId;
        Map<String, Object> currentPermission = getCurrentPermission(attachUrl, token);

        List<String> policies = new ArrayList<>();
        Object existing = currentPermission.get("policies");
        if (existing instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    policies.add(s);
                }
            }
        }
        policies.add("auth-server-token-exchange-policy");

        Map<String, Object> updateCfg = new LinkedHashMap<>();
        updateCfg.put("name", currentPermission.get("name"));
        updateCfg.put("description", currentPermission.get("description"));
        updateCfg.put("type", currentPermission.get("type"));
        updateCfg.put("logic", currentPermission.get("logic"));
        updateCfg.put("decisionStrategy", currentPermission.get("decisionStrategy"));
        updateCfg.put("resources", currentPermission.get("resources"));
        updateCfg.put("scopes", currentPermission.get("scopes"));
        updateCfg.put("policies", policies);
        makeRequest("PUT", attachUrl, updateCfg, token, "attach policy to token exchange permission");
    }

    @SuppressWarnings("unchecked")
    private String getClientInternalId(String tenantCode, String clientId, String token) {
        String url = baseUrl + "/admin/realms/" + tenantCode + "/clients?clientId="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .GET().build();
        HttpResponse<byte[]> resp = send(req, "get client");
        if (resp.statusCode() != 200) {
            throw new RuntimeException("failed to get client, status: " + resp.statusCode()
                    + ", body: " + new String(resp.body(), StandardCharsets.UTF_8));
        }
        List<Map<String, Object>> clients;
        try {
            clients = objectMapper.readValue(resp.body(), List.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse clients response: " + e.getMessage(), e);
        }
        if (clients.isEmpty()) {
            throw new RuntimeException("client " + clientId + " not found");
        }
        Object id = clients.get(0).get("id");
        if (id instanceof String s) {
            return s;
        }
        throw new RuntimeException("client ID not found in response");
    }

    @SuppressWarnings("unchecked")
    private String getTokenExchangePermissionId(String tenantCode, String realmMgmtClientId, String token) {
        String url = baseUrl + "/admin/realms/" + tenantCode + "/clients/" + realmMgmtClientId
                + "/authz/resource-server/permission";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .GET().build();
        HttpResponse<byte[]> resp = send(req, "get permissions");
        if (resp.statusCode() != 200) {
            throw new RuntimeException("failed to get permissions, status: " + resp.statusCode()
                    + ", body: " + new String(resp.body(), StandardCharsets.UTF_8));
        }
        List<Map<String, Object>> permissions;
        try {
            permissions = objectMapper.readValue(resp.body(), List.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse permissions response: " + e.getMessage(), e);
        }
        for (Map<String, Object> p : permissions) {
            Object name = p.get("name");
            if (name instanceof String s && s.startsWith("token-exchange.permission.idp.")) {
                Object id = p.get("id");
                if (id instanceof String idStr) {
                    return idStr;
                }
            }
        }
        List<String> available = new ArrayList<>();
        for (Map<String, Object> p : permissions) {
            if (p.get("name") instanceof String s) {
                available.add(s);
            }
        }
        throw new RuntimeException("token exchange permission for citizen IdP not found. Available permissions: "
                + available);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCurrentPermission(String url, String token) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .GET().build();
        HttpResponse<byte[]> resp = send(req, "get permission");
        if (resp.statusCode() != 200) {
            throw new RuntimeException("failed to get permission, status: " + resp.statusCode()
                    + ", body: " + new String(resp.body(), StandardCharsets.UTF_8));
        }
        try {
            return objectMapper.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse permission response: " + e.getMessage(), e);
        }
    }

    private void makeRequest(String method, String url, Object payload, String token, String operation) {
        byte[] body = new byte[0];
        if (payload != null) {
            try {
                body = objectMapper.writeValueAsBytes(payload);
            } catch (Exception e) {
                throw new RuntimeException("failed to marshal " + operation + " payload: " + e.getMessage(), e);
            }
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> resp = send(req, operation);

        int[] expected = switch (method) {
            case "POST" -> new int[]{201, 204};
            case "PUT" -> new int[]{200, 201, 204};
            case "GET" -> new int[]{200};
            default -> new int[]{200, 201, 204};
        };
        boolean success = false;
        for (int code : expected) {
            if (resp.statusCode() == code) {
                success = true;
                break;
            }
        }
        if (!success) {
            throw new RuntimeException("failed to " + operation + ", status: " + resp.statusCode()
                    + ", body: " + new String(resp.body(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Enables or disables the realm, which is what makes a tenant's isActive flag mean something:
     * a disabled realm rejects every authentication attempt for that tenant, so deactivation
     * actually locks the tenant's users out rather than only recording an intent.
     *
     * <p>{@code realm} is sent alongside {@code enabled} because Keycloak treats the body as a
     * partial RealmRepresentation keyed by that name; omitting it is rejected as a rename.
     */
    public void setRealmEnabled(String realmName, boolean enabled) {
        String token;
        try {
            token = getAdminToken();
        } catch (RuntimeException e) {
            throw new RuntimeException("failed to get admin token: " + cause(e), e);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("realm", realmName);
        payload.put("enabled", enabled);
        makeRequest("PUT", baseUrl + "/admin/realms/" + realmName, payload, token,
                (enabled ? "enable" : "disable") + " realm");
    }

    public void deleteRealm(String realmName) {
        String token;
        try {
            token = getAdminToken();
        } catch (RuntimeException e) {
            // Mirror Go: fmt.Errorf("failed to get admin token: %v", err)
            throw new RuntimeException("failed to get admin token: " + cause(e), e);
        }
        String url = baseUrl + "/admin/realms/" + realmName;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .DELETE().build();
        HttpResponse<byte[]> resp = send(req, "delete realm");
        if (resp.statusCode() != 204 && resp.statusCode() != 404) {
            throw new RuntimeException("failed to delete realm, status: " + resp.statusCode()
                    + ", body: " + new String(resp.body(), StandardCharsets.UTF_8));
        }
    }

    private HttpResponse<byte[]> send(HttpRequest req, String operation) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            // Mirror Go: fmt.Errorf("failed to %s: %v", operation, err) — never embed "null".
            throw new RuntimeException("failed to " + operation + ": " + cause(e), e);
        }
    }

    /**
     * Returns a non-empty message for an exception, mirroring Go where wrapping with {@code %v}
     * never yields the empty string. Falls back to the type name when the JVM leaves
     * {@code getMessage()} null (common for some ConnectException paths) so the wrapped
     * outbound-failure descriptions never read ": null".
     */
    private static String cause(Throwable t) {
        if (t == null) {
            return "";
        }
        String m = t.getMessage();
        if (m != null && !m.isEmpty()) {
            return m;
        }
        return t.getClass().getName();
    }
}
