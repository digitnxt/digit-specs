package org.digit.billing.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.digit.billing.model.ErrorCodes;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Required-header enforcement + request-header echo (port of the Go middleware).
 * X-Tenant-ID is required on every /v3/** route; X-User-ID additionally on all
 * writes (every non-GET route in the Go route table requires both). The tracer
 * filter already handles X-Correlation-ID; this echoes the remaining ids.
 * Q8: missing headers respond with the standard tracer array, not Go's bare object.
 */
public class HeaderInterceptor implements HandlerInterceptor {

    public static final String TENANT_ID = "X-Tenant-ID";
    public static final String USER_ID = "X-User-ID";
    public static final String REQUEST_ID = "X-Request-ID";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        echo(request, response, TENANT_ID, USER_ID, REQUEST_ID);

        if (!request.getRequestURI().contains("/v3/")) {
            return true;
        }

        List<String> missing = new ArrayList<>();
        if (isBlank(request.getHeader(TENANT_ID))) {
            missing.add(TENANT_ID);
        }
        if (!"GET".equals(request.getMethod()) && isBlank(request.getHeader(USER_ID))) {
            missing.add(USER_ID);
        }
        if (!missing.isEmpty()) {
            throw new CustomException(ErrorCodes.MISSING_HEADER, "Missing required header",
                    null, missing, HttpStatus.BAD_REQUEST);
        }
        return true;
    }

    private static void echo(HttpServletRequest request, HttpServletResponse response, String... headers) {
        for (String header : headers) {
            String value = request.getHeader(header);
            if (!isBlank(value)) {
                response.setHeader(header, value.trim());
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
