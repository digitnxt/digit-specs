package org.digit.notify.app.controller.dto;

import org.digit.notify.app.domain.entity.config.ChannelsConfig;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record NotificationConfigResponseDto(
    UUID id,
    String tenantId,
    String templateCode,
    boolean isActive,
    ChannelsConfig channels,
    @Nullable String tag
) {}
