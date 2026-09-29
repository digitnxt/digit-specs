package org.digit.idgen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.model.SequenceScope;
import org.digit.idgen.model.TemplateConfig;
import org.digit.idgen.model.TemplateRow;
import org.digit.idgen.repo.SequenceRepository;
import org.digit.idgen.repo.TemplateRepository;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

class GenerationServiceTest {

    // 2027-01-15 UTC
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2027-01-15T10:00:00Z"), ZoneOffset.UTC);

    private TemplateRepository templates;
    private SequenceRepository sequences;
    private GenerationService service;

    @BeforeEach
    void setUp() {
        templates = mock(TemplateRepository.class);
        sequences = mock(SequenceRepository.class);
        service = new GenerationService(templates, sequences, new SimpleMeterRegistry(), CLOCK);
    }

    private void givenLatest(String template, TemplateConfig.Sequence seq, TemplateConfig.Random random) {
        var config = new TemplateConfig(template, seq, random);
        when(templates.findLatest("pb", "receipt-id")).thenReturn(Optional.of(
                new TemplateRow(UUID.randomUUID(), "pb", "receipt-id", 2, config,
                        1L, "c", 2L, "m", null)));
    }

    private static Dtos.GenerateRequest generateRequest(Map<String, String> vars) {
        return new Dtos.GenerateRequest("receipt-id", vars);
    }

    @Test
    void unknownTemplateIs404() {
        when(templates.findLatest(anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generate("pb", generateRequest(Map.of())))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void nullSequenceConfigIs422() {
        givenLatest("R-{SEQ}", null, new TemplateConfig.Random(2, "A-Z"));
        assertThatThrownBy(() -> service.generate("pb", generateRequest(Map.of())))
                .hasMessage("id generation failed: template \"receipt-id\" has no sequence config");
    }

    @Test
    void nullRandomConfigIs422() {
        givenLatest("R-{SEQ}", new TemplateConfig.Sequence(SequenceScope.GLOBAL, 1, null), null);
        assertThatThrownBy(() -> service.generate("pb", generateRequest(Map.of())))
                .hasMessage("id generation failed: template \"receipt-id\" has no random config");
    }

    @Test
    void globalTemplateUsesPostgresSequenceWithPaddingAndDate() {
        givenLatest("R-{DATE:yyyymmdd}-{SEQ}",
                new TemplateConfig.Sequence(SequenceScope.GLOBAL, 1, new TemplateConfig.Padding(4, "0")),
                new TemplateConfig.Random(2, "A-Z0-9"));
        when(sequences.nextValue("seq_v1_c6bbf4885c55c29a2aa400ae7166913d20bf7ebe")).thenReturn(42L);

        var response = service.generate("pb", generateRequest(Map.of()));
        assertThat(response.id()).isEqualTo("R-20270115-0042");
        assertThat(response.version()).isEqualTo("v2");
    }

    @Test
    void scopedTemplateReservesFromCounterWithScopeKey() {
        givenLatest("R-{SEQ}",
                new TemplateConfig.Sequence(SequenceScope.DAILY, 10, null),
                new TemplateConfig.Random(2, "A-Z0-9"));
        when(sequences.reserveScoped("pb", "receipt-id", "2027-01-15", 10, 1)).thenReturn(10L);

        assertThat(service.generate("pb", generateRequest(Map.of())).id()).isEqualTo("R-10");
    }

    @Test
    void templateWithoutSeqNeverTouchesSequences() {
        givenLatest("R-{ORG}",
                new TemplateConfig.Sequence(SequenceScope.GLOBAL, 1, null),
                new TemplateConfig.Random(2, "A-Z0-9"));
        assertThat(service.generate("pb", generateRequest(Map.of("ORG", "X"))).id()).isEqualTo("R-X");
        verifyNoInteractions(sequences);
    }

    @Test
    void legacyRowWithNullNestedFieldsIsNormalizedNotNpe() {
        // hand-inserted JSONB {"sequence":{"scope":"DAILY"},"random":{}} — nulls inside blocks
        givenLatest("R-{SEQ}-{RAND}",
                new TemplateConfig.Sequence(SequenceScope.DAILY, null, null),
                new TemplateConfig.Random(null, null));
        when(sequences.reserveScoped("pb", "receipt-id", "2027-01-15", 1, 1)).thenReturn(1L);

        var response = service.generate("pb", generateRequest(Map.of()));
        assertThat(response.id()).matches("R-1-[A-Z0-9]{2}");
    }

    @Test
    void bulkReturnsContiguousSequenceOrder() {
        givenLatest("R-{SEQ}",
                new TemplateConfig.Sequence(SequenceScope.MONTHLY, 1, new TemplateConfig.Padding(3, "0")),
                new TemplateConfig.Random(2, "A-Z0-9"));
        when(sequences.reserveScoped("pb", "receipt-id", "2027-01", 1, 3)).thenReturn(5L);

        var response = service.bulkGenerate("pb", new Dtos.BulkGenerateRequest("receipt-id", 3, Map.of()));
        assertThat(response.count()).isEqualTo(3);
        assertThat(response.ids()).containsExactly("R-005", "R-006", "R-007");
    }

    /**
     * Allocation failure is a 422, and is raised on the FIRST failure — the 5x/200ms retry
     * was removed with the tenant-migration adoption. Under that library's request-wide
     * transaction the retry could not do its job anyway: a database that cannot be reached
     * fails the request at transaction-open in the filter, and a connection lost mid-request
     * is pinned by the transaction, so every attempt reused the dead connection. Verified
     * here by stubbing a single throw and asserting exactly one call.
     */
    @Test
    void allocationFailureIs422WithoutRetrying() {
        givenLatest("R-{SEQ}",
                new TemplateConfig.Sequence(SequenceScope.GLOBAL, 1, null),
                new TemplateConfig.Random(2, "A-Z0-9"));
        when(sequences.nextValue(anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.generate("pb", generateRequest(Map.of())))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).startsWith("id generation failed: nextval failed:");
                    assertThat(e.getMessage()).contains("db down");
                });
        verify(sequences, times(1)).nextValue(anyString());
    }

    @Test
    void bulkWithoutSeqUsesNoAllocation() {
        givenLatest("R-{RAND}",
                new TemplateConfig.Sequence(SequenceScope.GLOBAL, 1, null),
                new TemplateConfig.Random(3, "0-9"));
        var response = service.bulkGenerate("pb", new Dtos.BulkGenerateRequest("receipt-id", 2, Map.of()));
        assertThat(response.ids()).hasSize(2).allMatch(id -> id.matches("R-[0-9]{3}"));
        verifyNoInteractions(sequences);
    }
}
