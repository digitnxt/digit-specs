package com.digit.individual.web;

import java.util.ArrayList;
import java.util.List;

import com.digit.individual.constants.ErrorCodes;
import com.digit.individual.constants.Headers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Required-header enforcement on the API group: X-Tenant-ID always, X-User-ID additionally on writes
 * (a GET stamps no createdBy/modifiedBy, so it does not need one).
 *
 * <p>An interceptor rather than a servlet filter on purpose. Interceptors run after handler mapping,
 * so a thrown {@link CustomException} is seen by the tracer's central advice and rendered in the
 * standard {@code [{code,message}]} envelope. A filter runs ahead of the DispatcherServlet and has to
 * hand-write that JSON, which is how the shapes drifted apart in the first place.
 *
 * <p>No header echoing here, or anywhere in this service. The tracer echoes X-Correlation-ID and
 * X-Tenant-ID, and the gateway adds X-Request-Id and X-User-ID to every response — verified against
 * billing and workflow, which echo nothing themselves yet return the identical header set.
 */
public class HeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        List<String> missing = new ArrayList<>();
        if (isBlank(request.getHeader(Headers.TENANT_ID))) {
            missing.add(Headers.TENANT_ID);
        }
        if (!"GET".equalsIgnoreCase(request.getMethod()) && isBlank(request.getHeader(Headers.USER_ID))) {
            missing.add(Headers.USER_ID);
        }
        if (!missing.isEmpty()) {
            throw new CustomException(ErrorCodes.MISSING_HEADER, "Missing required header",
                    null, missing, HttpStatus.BAD_REQUEST);
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}