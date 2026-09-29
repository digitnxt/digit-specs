package org.digit.idgen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.digit.idgen.model.TemplateConfig;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

class TemplateRendererTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 5);

    // ── all 30 date format keywords ───────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "yyyymmdd|20260705", "ddmmyyyy|05072026", "mmddyyyy|07052026",
            "yymmdd|260705", "ddmmyy|050726", "mmddyy|070526",
            "yyyy-mm-dd|2026-07-05", "dd-mm-yyyy|05-07-2026", "mm-dd-yyyy|07-05-2026",
            "yy-mm-dd|26-07-05", "dd-mm-yy|05-07-26",
            "yyyy/mm/dd|2026/07/05", "dd/mm/yyyy|05/07/2026", "mm/dd/yyyy|07/05/2026",
            "yy/mm/dd|26/07/05", "dd/mm/yy|05/07/26",
            "yyyy.mm.dd|2026.07.05", "dd.mm.yyyy|05.07.2026", "mm.dd.yyyy|07.05.2026",
            "yy.mm.dd|26.07.05", "dd.mm.yy|05.07.26",
            "mmyyyy|072026", "mm-yyyy|07-2026", "mm/yyyy|07/2026", "mm.yyyy|07.2026",
            "yyyy-mm|2026-07", "yyyy/mm|2026/07", "yyyy.mm|2026.07",
            "yyyy|2026", "yy|26"})
    void dateFormats(String keyword, String expected) {
        assertThat(render("{DATE:" + keyword + "}")).isEqualTo(expected);
    }

    @Test
    void dateKeywordIsCaseInsensitive() {
        assertThat(render("{date:YYYYMMDD}")).isEqualTo("20260705");
        assertThat(render("{Date:yyyy}")).isEqualTo("2026");
    }

    @Test
    void unknownDateFormatIs400() {
        assertThatThrownBy(() -> render("{DATE:xyz}"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("invalid template config: unknown date format \"xyz\"");
                });
    }

    // ── tokens ────────────────────────────────────────────────────────────────

    @Test
    void seqAndRandAreCaseInsensitive() {
        assertThat(TemplateRenderer.render("{seq}-{Rand}", "007", "XY", null, DAY)).isEqualTo("007-XY");
    }

    @Test
    void variablesAreCaseSensitive() {
        assertThat(TemplateRenderer.render("{ORG}", null, null, Map.of("ORG", "pb"), DAY)).isEqualTo("pb");
        assertThatThrownBy(() -> TemplateRenderer.render("{org}", null, null, Map.of("ORG", "pb"), DAY))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("UNPROCESSABLE");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage())
                            .isEqualTo("id generation failed: no value supplied for variable {org}");
                });
    }

    @Test
    void nullVariableValueRendersEmpty() {
        // JSON {"ORG": null} — Go's encoding/json produced "" here
        var vars = new HashMap<String, String>();
        vars.put("ORG", null);
        assertThat(TemplateRenderer.render("X-{ORG}-Y", null, null, vars, DAY)).isEqualTo("X--Y");
    }

    @Test
    void variableValuesWithRegexSpecialsAreLiteral() {
        assertThat(TemplateRenderer.render("{V}", null, null, Map.of("V", "a$1\\b"), DAY)).isEqualTo("a$1\\b");
    }

    @Test
    void staticTextAndMixedTemplate() {
        String out = TemplateRenderer.render("{ORG}-{DATE:yyyymmdd}-{SEQ}-{RAND}", "0042", "A9",
                Map.of("ORG", "PB"), DAY);
        assertThat(out).isEqualTo("PB-20260705-0042-A9");
    }

    @Test
    void usesTokenDetection() {
        assertThat(TemplateRenderer.usesToken("A-{SEQ}-B", "SEQ")).isTrue();
        assertThat(TemplateRenderer.usesToken("A-{seq}-B", "SEQ")).isTrue();
        assertThat(TemplateRenderer.usesToken("A-{SEQX}-B", "SEQ")).isFalse();
        assertThat(TemplateRenderer.usesToken("A-B", "SEQ")).isFalse();
    }

    // ── padding ───────────────────────────────────────────────────────────────

    @Test
    void paddingBehavior() {
        var pad4 = new TemplateConfig.Padding(4, "0");
        assertThat(TemplateRenderer.formatSequence(7, pad4)).isEqualTo("0007");
        assertThat(TemplateRenderer.formatSequence(12345, pad4)).isEqualTo("12345"); // no truncation
        assertThat(TemplateRenderer.formatSequence(7, null)).isEqualTo("7");          // padding omitted
        assertThat(TemplateRenderer.formatSequence(7, new TemplateConfig.Padding(3, "X"))).isEqualTo("XX7");
    }

    // ── charset ───────────────────────────────────────────────────────────────

    @Test
    void charsetExpansion() {
        assertThat(TemplateRenderer.expandCharset("A-C")).isEqualTo("ABC");
        assertThat(TemplateRenderer.expandCharset("A-C0-2x")).isEqualTo("ABC012x");
        assertThat(TemplateRenderer.expandCharset("ABC")).isEqualTo("ABC");
    }

    @Test
    void charsetErrors() {
        assertThatThrownBy(() -> TemplateRenderer.expandCharset("Z-A"))
                .hasMessage("invalid template config: invalid charset range Z-A (start > end)");
        assertThatThrownBy(() -> TemplateRenderer.expandCharset("A-z"))
                .hasMessage("invalid template config: cross-class charset range A-z not allowed");
        assertThatThrownBy(() -> TemplateRenderer.expandCharset(""))
                .hasMessage("invalid template config: charset is empty");
    }

    @Test
    void randomStringRespectsLengthAndCharset() {
        var out = TemplateRenderer.randomString(new TemplateConfig.Random(6, "A-B"));
        assertThat(out).hasSize(6).matches("[AB]{6}");
    }

    // ── semantic validation & defaults ────────────────────────────────────────

    @Test
    void validateRejectsPaddingShorterThanStartDigits() {
        var cfg = new TemplateConfig("AB-{SEQ}",
                new TemplateConfig.Sequence(null, 100, new TemplateConfig.Padding(2, "0")),
                null).normalized();
        assertThatThrownBy(() -> TemplateRenderer.validate(cfg))
                .hasMessage("invalid template config: padding length (2) is shorter than "
                        + "start value digits (3) — padding has no effect");
    }

    @Test
    void validateRejectsUnknownDateKeywordAtCreateTime() {
        var cfg = new TemplateConfig("AB-{DATE:nope}", null, null).normalized();
        assertThatThrownBy(() -> TemplateRenderer.validate(cfg))
                .hasMessage("invalid template config: unknown date format \"nope\"");
    }

    @Test
    void normalizedAppliesGoDefaults() {
        var cfg = new TemplateConfig("AB-{SEQ}", null, null).normalized();
        assertThat(cfg.sequence().scope()).hasToString("GLOBAL");
        assertThat(cfg.sequence().start()).isEqualTo(1);
        assertThat(cfg.sequence().padding()).isNull();       // omitted padding stays null
        assertThat(cfg.random().length()).isEqualTo(2);
        assertThat(cfg.random().charset()).isEqualTo("A-Z0-9");

        var partial = new TemplateConfig("AB-{SEQ}",
                new TemplateConfig.Sequence(null, null, new TemplateConfig.Padding(null, null)),
                null).normalized();
        assertThat(partial.sequence().padding().length()).isEqualTo(4);
        assertThat(partial.sequence().padding().character()).isEqualTo("0");
    }

    private static String render(String pattern) {
        return TemplateRenderer.render(pattern, "1", "R", Map.of(), DAY);
    }
}
