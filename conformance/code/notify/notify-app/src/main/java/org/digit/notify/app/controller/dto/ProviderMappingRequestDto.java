package org.digit.notify.app.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import java.util.List;

@NullMarked
public record ProviderMappingRequestDto(
    @NotBlank String channel,
    @Nullable @Size(min = 1) String country,
    @NotEmpty List<String> providers
) {}
