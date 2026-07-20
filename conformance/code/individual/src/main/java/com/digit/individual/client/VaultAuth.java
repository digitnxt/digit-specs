package com.digit.individual.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages a Vault token obtained via AppRole login and refreshes it on demand
 * (see {@link #ensureToken()}) — there is no background thread. The service only
 * ever performs encrypt/decrypt; all key lifecycle (rotation, config) is managed
 * by Vault itself. Mirrors Go internal/clients/vault_auth.go (Option B renewal).
 */
public class VaultAuth {

    private final String address;
    private final String roleId;
    private final String secretId;
    private final HttpClient httpClient;
    private final JsonMapper mapper;

    private final Object lock = new Object();
    private volatile String token;
    private long ttlSeconds;
    private boolean renewable;
    private Instant expiresAt = Instant.EPOCH;

    public VaultAuth(String address, String roleId, String secretId, HttpClient httpClient, JsonMapper mapper) {
        this.address = trimRight(address);
        this.roleId = roleId;
        this.secretId = secretId;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    /** Returns the current Vault token. */
    public String token() {
        return token;
    }

    /** Performs an AppRole login and stores the resulting token. */
    public void login() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role_id", roleId);
        body.put("secret_id", secretId);
        JsonNode auth = call(address + "/v1/auth/approle/login", body, null).get("auth");
        applyAuth(auth, true);
    }

    /** Extends the lifetime of the current token via renew-self. */
    private void renewSelf() {
        JsonNode auth = call(address + "/v1/auth/token/renew-self", new LinkedHashMap<>(), token).get("auth");
        applyAuth(auth, false);
    }

    private void applyAuth(JsonNode auth, boolean requireToken) {
        if (auth == null) {
            throw new RuntimeException("vault auth: missing auth block in response");
        }
        JsonNode ctNode = auth.get("client_token");
        String clientToken = ctNode == null ? null : ctNode.asString();
        if (requireToken && (clientToken == null || clientToken.isEmpty())) {
            throw new RuntimeException("vault approle login: empty client_token");
        }
        synchronized (lock) {
            if (clientToken != null && !clientToken.isEmpty()) {
                this.token = clientToken;
            }
            JsonNode renew = auth.get("renewable");
            this.renewable = renew != null && Boolean.parseBoolean(renew.asString());
            JsonNode lease = auth.get("lease_duration");
            long ttl = 0;
            if (lease != null) {
                try {
                    ttl = Long.parseLong(lease.asString());
                } catch (NumberFormatException ignored) {
                    ttl = 0;
                }
            }
            this.ttlSeconds = ttl;
            this.expiresAt = Instant.now().plusSeconds(ttl);
        }
    }

    /**
     * Ensures a usable token is available before a Vault call. The common case is a
     * cheap in-memory check; a network call (renew or re-login) only happens when the
     * token is close to expiry or already gone.
     */
    public void ensureToken() {
        String tok;
        long ttl;
        boolean canRenew;
        Instant exp;
        synchronized (lock) {
            tok = this.token;
            ttl = this.ttlSeconds;
            canRenew = this.renewable;
            exp = this.expiresAt;
        }

        if (tok == null || tok.isEmpty()) {
            login();
            return;
        }
        // A non-positive TTL means a non-expiring token; nothing to do.
        if (ttl <= 0) {
            return;
        }
        long remaining = Instant.now().until(exp, ChronoUnit.SECONDS);
        if (remaining <= 0) {
            login();
            return;
        }
        // Refresh proactively once less than a quarter of the TTL remains.
        if (remaining < ttl / 4) {
            if (canRenew) {
                try {
                    renewSelf();
                } catch (RuntimeException e) {
                    login();
                }
            } else {
                login();
            }
        }
    }

    private JsonNode call(String url, Map<String, Object> body, String vaultToken) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("failed to marshal auth request: " + e.getMessage());
        }

        HttpResponse<String> resp;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (vaultToken != null) {
                builder.header("X-Vault-Token", vaultToken);
            }
            resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("vault auth request failed: " + e.getMessage());
        }

        if (resp.statusCode() != 200) {
            throw new RuntimeException("vault auth status " + resp.statusCode() + ": " + resp.body());
        }
        try {
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new RuntimeException("failed to decode auth response: " + e.getMessage());
        }
    }

    private static String trimRight(String s) {
        while (s != null && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
