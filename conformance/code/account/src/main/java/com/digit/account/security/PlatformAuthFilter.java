package com.digit.account.security;

import com.digit.account.config.AccountProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Gates the endpoints that cannot be protected by the gateway.
 *
 * <p>Two independent concerns, both here so there is exactly one place to look for "what is open":
 * the tenant-lifecycle endpoints require a platform-realm token, and self-service registration can
 * be switched off entirely.
 *
 * <p>Paths are matched with the canonical API prefix stripped, so one rule covers both the
 * header-based {@code /v3/tenants} and the envelope-based {@code /v3/<prefix>/tenants}. Without that,
 * every rule would need a twin and the canonical dialect would be a bypass waiting to be forgotten.
 */
public class PlatformAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthFilter.class);

    private static final String SIGNUP_PATH = "/v3/tenants/registrations";

    private final AccountProperties props;
    private final PlatformTokenVerifier verifier;
    private final List<Rule> rules;
    private final String canonicalSegment;

    private record Rule(String method, String[] segments) {}

    public PlatformAuthFilter(AccountProperties props, PlatformTokenVerifier verifier) {
        this.props = props;
        this.verifier = verifier;
        this.canonicalSegment = "/" + props.getServer().getCanonicalApiPrefix();
        this.rules = parse(props.getAuth().getProtectedEndpoints());

        if (!props.getAuth().isEnabled()) {
            log.warn("account.auth.enabled is false: tenant create, update and delete are reachable "
                    + "without a platform token. This is intended for local development only.");
        }
        if (props.getAuth().isEnabled() && props.getSignup().isEnabled()) {
            log.warn("Self-service registration is enabled while the tenant-lifecycle endpoints are "
                    + "locked. POST /v3/tenants/registrations/verify creates a tenant through the same "
                    + "path as POST /v3/tenants, so tenant creation remains reachable without a "
                    + "platform token. Set account.signup.enabled=false where that is not wanted.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = normalise(request.getServletPath());

        if (!props.getSignup().isEnabled() && path.startsWith(SIGNUP_PATH)) {
            deny(response, HttpServletResponse.SC_FORBIDDEN, "SIGNUP_DISABLED",
                    "Self-service registration is disabled on this environment.");
            return;
        }

        if (props.getAuth().isEnabled() && matches(request.getMethod(), path)) {
            try {
                String subject = verifier.verify(request.getHeader("Authorization"));
                log.debug("platform-authorised {} {} for subject {}", request.getMethod(), path, subject);
            } catch (PlatformTokenVerifier.Unauthenticated e) {
                log.warn("rejected {} {}: {}", request.getMethod(), path, e.getMessage());
                response.setHeader("WWW-Authenticate",
                        "Bearer realm=\"" + verifier.expectedIssuer() + "\"");
                deny(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                        "A verified platform-realm bearer token is required for this endpoint.");
                return;
            } catch (PlatformTokenVerifier.Forbidden e) {
                log.warn("rejected {} {}: {}", request.getMethod(), path, e.getMessage());
                deny(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                        "This endpoint requires the " + props.getAuth().getRequiredRole()
                                + " role in the platform realm.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Drops the canonical prefix so {@code /v3/<prefix>/tenants} and {@code /v3/tenants} match alike. */
    private String normalise(String servletPath) {
        String p = servletPath == null ? "" : servletPath;
        int at = p.indexOf(canonicalSegment + "/");
        if (at >= 0) {
            return p.substring(0, at) + p.substring(at + canonicalSegment.length());
        }
        return p;
    }

    private boolean matches(String method, String path) {
        String[] actual = split(path);
        for (Rule rule : rules) {
            if (!rule.method().equalsIgnoreCase(method) || rule.segments().length != actual.length) {
                continue;
            }
            boolean hit = true;
            for (int i = 0; i < actual.length; i++) {
                String want = rule.segments()[i];
                if (!"*".equals(want) && !want.equals(actual[i])) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private static List<Rule> parse(List<String> configured) {
        List<Rule> out = new ArrayList<>();
        for (String entry : configured == null ? List.<String>of() : configured) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new IllegalStateException("account.auth.protected-endpoints entries must look "
                        + "like \"METHOD:/path\" (got \"" + entry + "\")");
            }
            out.add(new Rule(entry.substring(0, colon).strip(),
                    split(entry.substring(colon + 1).strip())));
        }
        return out;
    }

    private static String[] split(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? new String[0] : p.split("/");
    }

    /** Matches the tracer's error shape: a bare array of {code, message}. */
    private static void deny(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("[{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}]");
    }
}