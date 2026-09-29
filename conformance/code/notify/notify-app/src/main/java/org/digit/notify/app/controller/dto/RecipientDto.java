package org.digit.notify.app.controller.dto;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@NullMarked
public record RecipientDto(
    @Nullable String phone,
    @Nullable String email,
    @Nullable List<String> deviceTokens,
    @Nullable String countryCode,
    @Nullable Map<String, Object> metadata
) {}
