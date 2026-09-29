package com.digit.account.security;

import com.digit.account.config.AccountProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies that a bearer token was issued by the platform realm and carries the role that authorises
 * tenant-lifecycle calls.
 *
 * <p>The signature is checked against the realm's published JWKS. Decoding the payload and comparing
 * the {@code iss} string would be worthless on its own: a JWT payload is base64, not a secret, so
 * anyone can write {@code {"iss":".../realms/master","realm_access":{"roles":["SUPERADMIN"]}}} and
 * be believed.
 *
 * <p>Two URLs are involved and they are not interchangeable. The {@code iss} claim carries the
 * browser-facing Keycloak hostname, so that is what the expected issuer must be built from. Keys are
 * fetched from the in-cluster hostname instead, so verification does not depend on the pod being
 * able to reach the public address.
 */
public class PlatformTokenVerifier {

    /** Distinguishes "no usable token" (401) from "token fine, wrong authority" (403). */
    public static final class Unauthenticated extends RuntimeException {
        public Unauthenticated(String message) {
            super(message);
        }
    }

    public static final class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    private final AccountProperties.Auth cfg;
    private final String expectedIssuer;
    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public PlatformTokenVerifier(AccountProperties props) {
        this.cfg = props.getAuth();
        this.expectedIssuer = resolveIssuer(props);
        String jwksUri = resolveJwksUri(props);

        URL jwks;
        try {
            jwks = URI.create(jwksUri).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("account.auth.jwks-uri is not a usable URL: " + jwksUri, e);
        }
        JWKSource<SecurityContext> keys = JWKSourceBuilder.create(jwks)
                .cache(cfg.getJwksCacheSeconds() * 1000L, 30_000L)
                .build();

        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // exp and iat are required so an unexpiring token cannot be presented; iss is pinned so a
        // token from a tenant realm — which any tenant admin can mint for themselves — is refused.
        //
        // No audience requirement on purpose: Keycloak's aud on an admin-cli token is not something
        // this service controls, and the two-argument constructor is the one that takes exact-match
        // claims. The three-argument overload's first parameter is the required *audience*, which is
        // an easy way to reject every real token while looking like an issuer check.
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().issuer(expectedIssuer).build(),
                Set.of("exp", "iat", "sub"));
        claims.setMaxClockSkew(cfg.getClockSkewSeconds());
        p.setJWTClaimsSetVerifier(claims);
        this.processor = p;
    }

    /** Returns the caller's subject on success. */
    public String verify(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new Unauthenticated("missing Authorization header");
        }
        String header = authorizationHeader.strip();
        if (header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new Unauthenticated("Authorization header must be a Bearer token");
        }
        String token = header.substring(7).strip();

        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (Exception e) {
            // Deliberately not echoing the token or the library's message verbatim to the caller;
            // the filter logs the detail and returns something generic.
            throw new Unauthenticated("token could not be verified: " + e.getMessage());
        }

        if (!realmRoles(claims).contains(cfg.getRequiredRole())) {
            throw new Forbidden("token lacks the " + cfg.getRequiredRole() + " realm role");
        }
        return claims.getSubject();
    }

    @SuppressWarnings("unchecked")
    private static List<String> realmRoles(JWTClaimsSet claims) {
        Object realmAccess = claims.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> roles) {
            return (List<String>) roles;
        }
        return List.of();
    }

    public String expectedIssuer() {
        return expectedIssuer;
    }

    private static String resolveIssuer(AccountProperties props) {
        String configured = props.getAuth().getIssuer();
        if (configured != null && !configured.isBlank()) {
            return trimSlashes(configured);
        }
        return trimSlashes(props.getKeycloak().getPublicBaseUrl())
                + "/realms/" + props.getAuth().getPlatformRealm();
    }

    private static String resolveJwksUri(AccountProperties props) {
        String configured = props.getAuth().getJwksUri();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return trimSlashes(props.getKeycloak().getBaseUrl())
                + "/realms/" + props.getAuth().getPlatformRealm()
                + "/protocol/openid-connect/certs";
    }

    private static String trimSlashes(String s) {
        String out = s == null ? "" : s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
