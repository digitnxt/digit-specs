package org.digit.notify.app.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * A send taken from the notify topic rather than from {@code POST /v3/notifications}.
 *
 * <p>{@code request} is the API's own {@link NotifyRequestDto}, unchanged, so one shape serves both
 * entry points and a caller can move between them without rewriting the payload. The tenant is
 * carried alongside it because the API reads it from {@code X-Tenant-ID} and a topic message has no
 * headers to read.
 *
 * <pre>
 * {"tenantId":"default","request":{"templateCode":"otp-login",
 *  "recipient":{"email":"someone@example.com"},"payload":{"otp":"123456"}}}
 * </pre>
 */
@NullMarked
public record NotifyMessageDto(
    @NotBlank String tenantId,
    @NotNull @Valid NotifyRequestDto request
) {}