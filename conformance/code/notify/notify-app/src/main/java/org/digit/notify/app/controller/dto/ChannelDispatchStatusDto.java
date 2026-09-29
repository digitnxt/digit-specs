package org.digit.notify.app.controller.dto;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record ChannelDispatchStatusDto(
    String channel,
    String status,
    String provider,
    @Nullable String reason
) {}
