package org.digit.idgen.web;

import static com.digit.tenant.migration.constants.ErrorCodes.INVALID_REQUEST;

import com.digit.tenant.migration.validators.TenantIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.digit.idgen.model.CanonicalDtos;
import org.digit.idgen.model.ErrorCodes;
import org.digit.tracer.model.Error;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Promotes the canonical tenant id back to an X-Tenant-ID header before anything
 * downstream looks for it.
 *
 * <p>Without this the canonical routes cannot work: tenant-migration's
 * TenantTransactionFilter (servlet order 40) runs ahead of the DispatcherServlet, demands
 * the header on every non-skipped path, and is what opens the transaction and points
 * search_path at the tenant schema. Apportion depends on that transaction for its
 * documented rollback-on-error behaviour — the request audit row must not survive a failed
 * request. Skipping the filter for canonical paths would answer at whatever schema the
 * pooled connection held; promoting the tenant keeps every downstream consumer working
 * unchanged.
 *
 * <p>Registered at order 0, ahead of the tracer's filter at 1, so the tracer's MDC and
 * response echo see the tenant too.
 *
 * <p>ponytail: the promoted header name is the tenant-migration default (X-Tenant-ID),
 * matching HeaderInterceptor.TENANT_ID. Read it from
 * {@code digit.tenant-migration.tenant-header} if a deployment ever renames it.
 */
public class CanonicalTenantFilter extends OncePerRequestFilter {

    private static final String START_TIME = CanonicalTenantFilter.class.getName() + ".start";
    private static final String REQUEST_INFO = CanonicalTenantFilter.class.getName() + ".requestMetadata";

    private final String prefix;
    private final ObjectMapper mapper;
    private final boolean tenantSeparation;

    public CanonicalTenantFilter(String canonicalPath, ObjectMapper mapper, boolean tenantSeparation) {
        this.prefix = "/v3/" + canonicalPath + "/";
        this.mapper = mapper;
        this.tenantSeparation = tenantSeparation;
    }

    /** Request metadata the filter parsed, for the error path that never reached binding. */
    static CanonicalDtos.RequestMetadata requestMetadata(HttpServletRequest request) {
        return request.getAttribute(REQUEST_INFO) instanceof CanonicalDtos.RequestMetadata in ? in : null;
    }

    /** Milliseconds since this filter saw the request, or null on a non-canonical route. */
    static Long elapsed(HttpServletRequest request, long now) {
        return request.getAttribute(START_TIME) instanceof Long start ? now - start : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith(prefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        request.setAttribute(START_TIME, System.currentTimeMillis());

        byte[] body = request.getInputStream().readAllBytes();
        String tenantId = readRequestMetadata(request, body);

        if (tenantId == null || tenantId.isBlank()) {
            writeError(request, response, ErrorCodes.MISSING_HEADER, "Missing required field",
                    List.of("requestMetadata.tenantId"));
            return;
        }
        // The same check tenant-migration runs at order 40, run here first purely so the
        // rejection can be written in the canonical envelope — that filter answers ahead of
        // the DispatcherServlet, where CanonicalExceptionAdvice cannot reach it. Gated on
        // separation for the same reason it is there: with it off the tenant never reaches
        // search_path, so ids legal on the 3.0 routes must stay legal here.
        if (tenantSeparation) {
            try {
                TenantIds.validate(tenantId);
            } catch (IllegalArgumentException e) {
                writeError(request, response, INVALID_REQUEST, e.getMessage(), null);
                return;
            }
        }
        chain.doFilter(new CanonicalRequest(request, body, tenantId), response);
    }

    /**
     * Reads the tenant out of the raw body and stashes the typed metadata for the error
     * path. Deliberately loose: an unparseable or ill-typed body is reported as a missing
     * tenant here, and @Valid reports the specific field once binding runs.
     */
    private String readRequestMetadata(HttpServletRequest request, byte[] body) {
        Map<?, ?> requestMetadata;
        try {
            requestMetadata = mapper.readValue(body, Map.class).get("requestMetadata") instanceof Map<?, ?> in ? in : null;
        } catch (Exception e) {
            return null;
        }
        if (requestMetadata == null) {
            return null;
        }
        try {
            request.setAttribute(REQUEST_INFO,
                    mapper.convertValue(requestMetadata, CanonicalDtos.RequestMetadata.class));
        } catch (Exception ignored) {
            // ill-typed metadata still yields the tenant below; @Valid reports the rest
        }
        return requestMetadata.get("tenantId") instanceof String tenantId ? tenantId : null;
    }

    /**
     * Written here rather than thrown: a servlet filter runs ahead of the DispatcherServlet
     * and CanonicalExceptionAdvice cannot see it. Same envelope the advice produces, so
     * every canonical error has one shape.
     */
    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            String code, String message, List<String> params) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        CanonicalDtos.ErrorResponse body = new CanonicalDtos.ErrorResponse(
                CanonicalController.responseMetadata(request, null, CanonicalDtos.Status.FAILED),
                List.of(new Error(code, message, null, params)));

        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
    }

    /** Context-relative, with the same empty-servlet-path fallback tenant-migration uses. */
    private static String path(HttpServletRequest request) {
        String path = request.getServletPath();
        return (path == null || path.isEmpty()) ? request.getRequestURI() : path;
    }

    /**
     * Carries the promoted header, and replays the body that was already consumed to read
     * it. Both are needed: the header for the filters downstream, the body for @RequestBody.
     */
    private static final class CanonicalRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private final String tenantId;

        CanonicalRequest(HttpServletRequest request, byte[] body, String tenantId) {
            super(request);
            this.body = body;
            this.tenantId = tenantId;
        }

        private static boolean isTenant(String name) {
            return HeaderInterceptor.TENANT_ID.equalsIgnoreCase(name);
        }

        @Override
        public String getHeader(String name) {
            return isTenant(name) ? tenantId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isTenant(name) ? Collections.enumeration(List.of(tenantId)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.add(HeaderInterceptor.TENANT_ID);
            return Collections.enumeration(names);
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** Fresh stream per call, so the body survives being read more than once. */
    private static final class CachedStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        CachedStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }

        @Override
        public int read() {
            return input.read();
        }
    }
}
