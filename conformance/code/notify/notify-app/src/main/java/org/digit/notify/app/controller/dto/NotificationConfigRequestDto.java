package org.digit.notify.app.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.digit.notify.app.domain.entity.config.ChannelsConfig;

public record NotificationConfigRequestDto(
    @NotBlank String templateCode,
    @NotNull ChannelsConfig channels,
    /** Optional grouping label, searchable on the list endpoint. */
    @Nullable String tag
) {}
