package com.digit.boundary.web;

import com.digit.boundary.constants.Headers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Echoes non-empty correlation headers (X-Tenant-Id, X-User-ID, X-Request-Id) back on the response.
 * Mirrors Go handlers.SetStandardResponseHeaders.
 */
public class ForwardHeadersFilter extends OncePerRequestFilter {

    private static final String[] FORWARDED = {Headers.TENANT_ID, Headers.USER_ID, Headers.REQUEST_ID};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        for (String h : FORWARDED) {
            String val = request.getHeader(h);
            if (val != null && !val.isEmpty()) {
                response.setHeader(h, val);
            }
        }
        filterChain.doFilter(request, response);
    }
}
