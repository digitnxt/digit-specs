package com.digit.individual.web;

import com.digit.individual.constants.ErrorCodes;
import com.digit.individual.constants.Headers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Validates required correlation headers on the API group, emitting the same tracer
 * {@code {"Errors":[...]}} envelope the tracer ExceptionAdvice produces for the rest of the API.
 * A servlet filter runs before the DispatcherServlet, so the advice cannot see exceptions thrown
 * here — the envelope is written directly to keep header errors consistent with everything else.
 */
public class HeaderValidationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = request.getHeader(Headers.TENANT_ID);
        String userId = request.getHeader(Headers.USER_ID);

        if (tenantId == null || tenantId.isEmpty()) {
            writeError(response, ErrorCodes.MISSING_HEADER, "Missing mandatory header: X-Tenant-ID");
            return;
        }
        // X-User-ID is required only for mutating operations (it stamps createdBy/modifiedBy);
        // read-only requests (GET) don't need it.
        if ((userId == null || userId.isEmpty()) && !"GET".equalsIgnoreCase(request.getMethod())) {
            writeError(response, ErrorCodes.MISSING_HEADER, "Missing required header: X-User-ID");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        String c = code.replace("\\", "\\\\").replace("\"", "\\\"");
        String m = message.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"Errors\":[{\"code\":\"" + c + "\",\"message\":\"" + m + "\"}]}");
    }
}
