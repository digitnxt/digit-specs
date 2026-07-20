package com.digit.individual.web;

import com.digit.individual.constants.Headers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Request-ID propagation. Mirrors Go middleware.RequestID (registered at the router root): if
 * X-Request-Id is absent a UUID is generated; the value is echoed back on the response and exposed
 * as a request attribute for downstream handlers.
 */
public class ForwardHeadersFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTR = "individual.requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(Headers.REQUEST_ID);
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(REQUEST_ID_ATTR, requestId);
        response.setHeader(Headers.REQUEST_ID, requestId);
        filterChain.doFilter(request, response);
    }
}
