package org.digit.idgen.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.digit.idgen.model.ErrorCodes;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Required-header enforcement + request-header echo (port of the Go middleware).
 * X-Tenant-ID is required on all /v3/** routes; X-User-ID additionally on template
 * writes (generation writes no audit records). The tracer filter already handles
 * X-Correlation-ID; this echoes the remaining ids the Go service echoed.
 */
public class HeaderInterceptor implements HandlerInterceptor {

    public static final String TENANT_ID = "X-Tenant-ID";
    public static final String USER_ID = "X-User-ID";
    public static final String REQUEST_ID = "X-Request-ID";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // X-Tenant-ID (and X-Correlation-ID) are echoed by the tracer filter already;
        // only the two ids the tracer doesn't own are echoed here
        echo(request, response, USER_ID, REQUEST_ID);

        String uri = request.getRequestURI();
        if (!uri.contains("/v3/")) {
            return true;
        }

        // sequential checks, tenant first — Go ran these as two separate middlewares,
        // so a request missing both headers reports only X-Tenant-ID
        if (isBlank(request.getHeader(TENANT_ID))) {
            throw missingHeader(TENANT_ID);
        }
        if (isTemplateWrite(request, uri) && isBlank(request.getHeader(USER_ID))) {
            throw missingHeader(USER_ID);
        }
        return true;
    }

    private static CustomException missingHeader(String header) {
        return new CustomException(ErrorCodes.MISSING_HEADER, "Missing required header",
                null, List.of(header), HttpStatus.BAD_REQUEST);
    }

    private static boolean isTemplateWrite(HttpServletRequest request, String uri) {
        return uri.endsWith("/v3/template")
                && switch (request.getMethod()) {
                    case "POST", "PUT", "DELETE" -> true;
                    default -> false;
                };
    }

    private static void echo(HttpServletRequest request, HttpServletResponse response, String... headers) {
        for (String header : headers) {
            String value = request.getHeader(header);
            if (!isBlank(value)) {
                response.setHeader(header, value.trim());
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
