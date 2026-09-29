package org.digit.idgen.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Template configuration, persisted as-is into the {@code config} JSONB column.
 * {@link #normalized()} applies the same defaults Go's ApplyDefaults did — the
 * normalized form is what gets stored, so all fields serialize (nulls included,
 * matching Go's marshaling; {@code padding} stays null when the caller omitted it).
 */
public record TemplateConfig(
        @NotBlank @Size(min = 2, max = 256) String template,
        @Valid Sequence sequence,
        @Valid Random random) {

    public TemplateConfig normalized() {
        return new TemplateConfig(
                template,
                (sequence == null ? new Sequence(null, null, null) : sequence).normalized(),
                (random == null ? new Random(null, null) : random).normalized());
    }

    public record Sequence(
            SequenceScope scope,
            @Min(1) Integer start,
            @Valid Padding padding) {

        Sequence normalized() {
            return new Sequence(
                    scope == null ? SequenceScope.GLOBAL : scope,
                    start == null ? 1 : start,
                    padding == null ? null : padding.normalized());
        }
    }

    public record Padding(
            @Min(1) @Max(10) Integer length,
            @Pattern(regexp = "^[A-Za-z0-9]$") @JsonProperty("char") String character) {

        Padding normalized() {
            return new Padding(length == null ? 4 : length, character == null ? "0" : character);
        }
    }

    public record Random(
            @Min(1) @Max(10) Integer length,
            @Pattern(regexp = "^([A-Z]-[A-Z]|[a-z]-[a-z]|[0-9]-[0-9]|[A-Za-z0-9])+$") String charset) {

        Random normalized() {
            return new Random(length == null ? 2 : length, charset == null ? "A-Z0-9" : charset);
        }
    }
}
