package org.digit.notify.app.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;

@NullMarked
public record NotifyRequestDto(
    @NotBlank String templateCode,
    @NotNull RecipientDto recipient,
    @NotNull Map<String, Object> payload,
    @Nullable String locale,
    @Nullable Map<String, Object> metadata
) {}
