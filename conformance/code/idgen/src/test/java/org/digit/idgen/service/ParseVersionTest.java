package org.digit.idgen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParseVersionTest {

    @Test
    void parsesValidVersions() {
        assertThat(TemplateService.parseVersion("v1")).isEqualTo(1);
        assertThat(TemplateService.parseVersion("v42")).isEqualTo(42);
        // Go's digit loop accepts leading zeros ("v01" → 1) — parity preserved
        assertThat(TemplateService.parseVersion("v01")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"V1", "v", "1", "vx", "v1x", ""})
    void rejectsMalformedVersions(String v) {
        assertThatThrownBy(() -> TemplateService.parseVersion(v))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getMessage()).isEqualTo(
                                "invalid template config: version must match ^v[1-9][0-9]*$, got \"" + v + "\""));
    }

    @Test
    void rejectsOverflowingVersionsInsteadOfAliasing() {
        // int wrap would alias v4294967297 onto v1 (and delete it); must 400 instead
        assertThatThrownBy(() -> TemplateService.parseVersion("v4294967297"))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> TemplateService.parseVersion("v2147483648"))
                .isInstanceOf(CustomException.class);
        assertThat(TemplateService.parseVersion("v2147483647")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void rejectsVersionZero() {
        assertThatThrownBy(() -> TemplateService.parseVersion("v0"))
                .hasMessage("invalid template config: version must be >= v1, got \"v0\"");
    }
}
