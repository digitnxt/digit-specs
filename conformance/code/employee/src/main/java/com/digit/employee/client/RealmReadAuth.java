package com.digit.employee.client;

import com.digit.employee.config.EmployeeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@code Authorization} header for read-only Keycloak realm lookups.
 *
 * <p>Read-only admin lookups (role members, role existence, user existence) need Keycloak
 * {@code realm-management} view permissions that an ordinary employee or citizen token does not
 * carry — forwarding the caller's token makes those lookups fail with 403 for everyone except realm
 * admins. So the reads use a dedicated service account (client_credentials) instead, while every
 * write keeps forwarding the caller's token so Keycloak still enforces that the caller may create
 * users and grant roles.
 *
 * <p>The token is issued by the tenant's own realm (the token endpoint is per-realm and the realm is
 * derived from the tenant), so the service-account client must exist in every tenant realm under the
 * same id and secret — it is provisioned as part of the realm config applied at realm creation.
 *
 * <p>Mirrors Go internal/clients/keycloak/realm_read_auth.go.
 */
@Component
public class RealmReadAuth {

    /**
     * How long before actual expiry a cached token is considered stale. Covers clock drift plus the
     * in-flight time of the request about to use it, so we never hand out a token that expires
     * mid-call.
     */
    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofSeconds(30);

    /** A service-account access token and the instant it goes stale. */
    private record CachedToken(String accessToken, Instant expiresAt) {}

    private final DownstreamHttp http;
    private final ObjectMapper objectMapper;
    private final String baseURL;
    private final String clientId;
    private final String clientSecret;

    /**
     * Tokens are realm-scoped (the token endpoint is {@code /realms/{realm}/...}), so a
     * single-entry cache would thrash on every tenant switch.
     */
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    /**
     * Per-realm fetch locks, so a cold cache opens one token request per realm rather than one per
     * in-flight lookup. Deliberately not {@code tokens.compute(...)}: that would run the HTTP fetch
     * while holding a ConcurrentHashMap bin lock, which the class documents as blocking unrelated
     * updates.
     */
    private final Map<String, Object> fetchLocks = new ConcurrentHashMap<>();

    public RealmReadAuth(EmployeeProperties props, ObjectMapper objectMapper, DownstreamHttp http) {
        this.objectMapper = objectMapper;
        this.http = http;
        String b = props.getKeycloak().getBaseUrl();
        this.baseURL = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.clientId = props.getKeycloak().getClientId();
        this.clientSecret = props.getKeycloak().getClientSecret();
    }

    /**
     * Whether a client id + secret were supplied. When they are absent the caller's token is
     * forwarded instead, which keeps existing deployments working (admin callers succeed, ordinary
     * callers get the 403 they got before) rather than failing every lookup.
     */
    private boolean configured() {
        return clientId != null && !clientId.isEmpty()
                && clientSecret != null && !clientSecret.isEmpty();
    }

    /**
     * Resolves the Authorization header for a read-only lookup in {@code realm}: the service-account
     * token when configured, otherwise {@code callerAuth} unchanged. A token fetch failure also
     * falls back to {@code callerAuth} rather than failing the request outright — the caller may
     * well be a realm admin whose own token would have worked.
     */
    public String forRealm(String realm, String callerAuth) {
        if (!configured()) {
            return callerAuth;
        }
        try {
            return "Bearer " + serviceToken(realm);
        } catch (RuntimeException e) {
            return callerAuth;
        }
    }

    /**
     * A valid service-account access token for the realm, fetching a new one only when the cache is
     * empty or the cached token is within {@link #TOKEN_REFRESH_SKEW} of expiry.
     */
    private String serviceToken(String realm) {
        CachedToken cached = tokens.get(realm);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.accessToken();
        }
        synchronized (fetchLocks.computeIfAbsent(realm, r -> new Object())) {
            // Re-check under the lock: another thread may have populated the entry while we waited.
            cached = tokens.get(realm);
            if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
                return cached.accessToken();
            }
            TokenResponse tr = fetchServiceToken(realm);
            Duration ttl = Duration.ofSeconds(tr.expiresIn());
            // Guard against a missing or nonsensical expires_in: don't cache something we can't
            // bound — drop any stale entry and treat this token as single-use.
            if (ttl.compareTo(TOKEN_REFRESH_SKEW) <= 0) {
                tokens.remove(realm);
                return tr.accessToken();
            }
            tokens.put(realm,
                    new CachedToken(tr.accessToken(), Instant.now().plus(ttl.minus(TOKEN_REFRESH_SKEW))));
            return tr.accessToken();
        }
    }

    /** The subset of Keycloak's token endpoint response we use. */
    private record TokenResponse(String accessToken, int expiresIn) {}

    /**
     * Performs the client_credentials grant against the realm's token endpoint.
     */
    private TokenResponse fetchServiceToken(String realm) {
        String reqURL = baseURL + "/realms/" + realm + "/protocol/openid-connect/token";
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
            HttpResponse<String> resp = http.send(req);
            if (resp.statusCode() != 200) {
                // The body can echo back client_id but never the secret; still, keep it out of the
                // error so a failed fetch can't leak request detail to logs.
                throw new RuntimeException("keycloak token endpoint returned status="
                        + resp.statusCode() + " for realm=" + realm);
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String token = node.path("access_token").asText("");
            if (token.isEmpty()) {
                throw new RuntimeException(
                        "keycloak token response contained no access_token for realm=" + realm);
            }
            return new TokenResponse(token, node.path("expires_in").asInt(0));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("keycloak token request failed: " + e.getMessage(), e);
        }
    }
}
