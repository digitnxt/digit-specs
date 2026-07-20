package com.digit.employee.web;

import com.digit.employee.constants.ErrorCodes;
import com.digit.employee.constants.Headers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.digit.tracer.model.CustomException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates the required request headers (X-Tenant-ID, then X-User-ID) for every request except the
 * health check, returning 400 with the tracer error envelope
 * {@code {"errors":[{"code":"MISSING_HEADER","message":"X-Tenant-ID header is required"}]}}.
 *
 * <p>Runs before the tenant transaction filter, mirroring the Go middleware order
 * (Logger -> Headers -> tenantdb.GinMiddleware). Because the filter runs ahead of the
 * DispatcherServlet, the tracer's ExceptionAdvice cannot see throws here, so the body is written
 * directly via {@link CustomException#toErrorResponse()} to keep the shape identical to the advice.
 */
public class HeadersFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public HeadersFilter(String contextPath, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Go skips the check only for the exact "/health" path.
        if ("/health".equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String tenantId = request.getHeader(Headers.TENANT_ID);
        if (tenantId == null || tenantId.isEmpty()) {
            writeError(response, "X-Tenant-ID header is required");
            return;
        }
        // X-User-ID is required only for mutating operations (it stamps createdBy/modifiedBy);
        // read-only GET requests don't need it (consistent with the individual service).
        String userId = request.getHeader(Headers.USER_ID);
        if ((userId == null || userId.isEmpty()) && !"GET".equalsIgnoreCase(request.getMethod())) {
            writeError(response, "X-User-ID header is required");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        // Same envelope the tracer ExceptionAdvice produces (defaults to 400).
        CustomException ex = new CustomException(ErrorCodes.MISSING_HEADER, message);
        response.getWriter().write(objectMapper.writeValueAsString(ex.toErrorResponse()));
    }
}
