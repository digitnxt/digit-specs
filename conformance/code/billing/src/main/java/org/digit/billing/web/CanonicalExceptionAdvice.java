package org.digit.billing.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.digit.billing.model.CanonicalDtos;
import org.digit.tracer.error.ExceptionAdvise;
import org.digit.tracer.model.Error;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Wraps the bare {@code [{code,message}]} array of the 3.0 routes in the canonical
 * {@code {responseMetadata, errors}} envelope — for the canonical controller only.
 *
 * <p>Delegates the mapping itself to the tracer's advice rather than repeating it, so the
 * 400/422 split, binding-error rendering, the error queue and the tracer.errors metric all
 * stay in one place and canonical errors carry the same codes as their 3.0 equivalents.
 *
 * <p>Ordered ahead of that advice, which sits at LOWEST_PRECEDENCE; scoped by
 * assignableTypes so every 3.0 route keeps returning the bare array.
 */
@RestControllerAdvice(assignableTypes = CanonicalController.class)
@Order(0)
public class CanonicalExceptionAdvice {

    private final ExceptionAdvise delegate;

    public CanonicalExceptionAdvice(ExceptionAdvise delegate) {
        this.delegate = delegate;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(HttpServletRequest request, Exception ex) {
        ResponseEntity<?> rendered = delegate.exceptionHandler(request, ex);
        // ServiceCallException renders an upstream service's own payload, which has no
        // errors array to wrap — passed through as the tracer built it.
        if (!(rendered.getBody() instanceof List<?> errors)) {
            return rendered;
        }
        @SuppressWarnings("unchecked")
        CanonicalDtos.ErrorResponse body = new CanonicalDtos.ErrorResponse(
                CanonicalController.responseMetadata(request, null, CanonicalDtos.Status.FAILED),
                (List<Error>) errors);
        return ResponseEntity.status(rendered.getStatusCode()).body(body);
    }
}
