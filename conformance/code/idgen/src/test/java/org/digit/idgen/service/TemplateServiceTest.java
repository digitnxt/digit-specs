package org.digit.idgen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.digit.idgen.events.TemplateEventPublisher;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.model.SequenceScope;
import org.digit.idgen.model.TemplateConfig;
import org.digit.idgen.model.TemplateRow;
import org.digit.idgen.repo.SequenceRepository;
import org.digit.idgen.repo.TemplateRepository;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class TemplateServiceTest {

    private TemplateRepository templates;
    private SequenceRepository sequences;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        templates = mock(TemplateRepository.class);
        sequences = mock(SequenceRepository.class);
        service = new TemplateService(templates, sequences, mock(TemplateEventPublisher.class),
                Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC));
    }

    private static TemplateRow row(int version, TemplateConfig config) {
        return new TemplateRow(UUID.randomUUID(), "pb", "receipt-id", version, config,
                100L, "creator", 200L, "modifier", null);
    }

    private static Dtos.TemplateRequest request(SequenceScope scope, Integer start) {
        return new Dtos.TemplateRequest("receipt-id",
                new TemplateConfig("R-{SEQ}", new TemplateConfig.Sequence(scope, start, null), null));
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void createRejectsDuplicateCode() {
        when(templates.existsByCode("pb", "receipt-id")).thenReturn(true);
        assertThatThrownBy(() -> service.create("pb", "u1", null, request(null, null)))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CONFLICT");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void createMapsInsertRaceTo409() {
        when(templates.existsByCode(anyString(), anyString())).thenReturn(false);
        doThrow(new DuplicateKeyException("unique_violation")).when(templates).insert(any());
        assertThatThrownBy(() -> service.create("pb", "u1", null, request(null, null)))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CONFLICT");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void createInitializesSequenceAtStart() {
        when(templates.existsByCode(anyString(), anyString())).thenReturn(false);
        var response = service.create("pb", "u1", "r-1", request(SequenceScope.GLOBAL, 100));
        assertThat(response.version()).isEqualTo("v1");
        verify(sequences).createSequence("seq_v1_c6bbf4885c55c29a2aa400ae7166913d20bf7ebe", 100);
        verify(sequences).insertLookup(anyString(), any(), any(), any());
    }

    // ── update: GLOBAL start-change rule ──────────────────────────────────────

    @Test
    void updateRejectsGlobalStartChange() {
        when(templates.findLatest("pb", "receipt-id"))
                .thenReturn(Optional.of(row(1, request(SequenceScope.GLOBAL, 1).config().normalized())));
        assertThatThrownBy(() -> service.update("pb", "u2", null, request(SequenceScope.GLOBAL, 5)))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                    assertThat(e.getMessage()).startsWith(
                            "invalid template config: cannot change start for GLOBAL-scoped templates (current value: 1)");
                });
    }

    @Test
    void updateAllowsScopedStartChangeAndPreservesCreationAudit() {
        when(templates.findLatest("pb", "receipt-id"))
                .thenReturn(Optional.of(row(3, request(SequenceScope.DAILY, 1).config().normalized())));
        var response = service.update("pb", "u2", null, request(SequenceScope.DAILY, 500));
        assertThat(response.version()).isEqualTo("v4");
        assertThat(response.auditDetail().createdBy()).isEqualTo("creator");
        assertThat(response.auditDetail().createdTime()).isEqualTo(100L);
        assertThat(response.auditDetail().modifiedBy()).isEqualTo("u2");
    }

    @Test
    void updateOfUnknownTemplateIs404() {
        when(templates.findLatest(anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("pb", "u2", null, request(null, null)))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void searchVersionWithoutCodeIs400() {
        assertThatThrownBy(() -> service.search("pb", null, "v1", List.of(), null, 0))
                .hasMessage("invalid template config: version requires templateCode");
    }

    @Test
    void searchParsesVersionBeforeBranching() {
        // ids branch would win, but a malformed version must still 400 (Go parse-first order)
        assertThatThrownBy(() -> service.search("pb", "receipt-id", "garbage",
                List.of(UUID.randomUUID()), null, 0))
                .hasMessageContaining("version must match");
    }

    @Test
    void searchIdsBranchWinsOverCode() {
        var id = UUID.randomUUID();
        when(templates.findByIds("pb", List.of(id))).thenReturn(List.of());
        assertThat(service.search("pb", "receipt-id", null, List.of(id), null, 0)).isEmpty();
        verify(templates).findByIds("pb", List.of(id));
        verify(templates, never()).findLatest(anyString(), anyString());
    }

    @Test
    void searchDefaultsLimitTo25() {
        when(templates.findAllLatest("pb", 25, 0)).thenReturn(List.of());
        service.search("pb", null, null, List.of(), null, 0);
        verify(templates).findAllLatest("pb", 25, 0);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteLastVersionCleansUpSequences() {
        when(templates.findByVersion("pb", "receipt-id", 1))
                .thenReturn(Optional.of(row(1, request(null, null).config().normalized())));
        when(templates.countVersions("pb", "receipt-id")).thenReturn(1L);
        service.delete("pb", "receipt-id", "v1");
        verify(sequences).dropSequence(anyString());
        verify(sequences).deleteLookup("pb", "receipt-id");
        verify(sequences).deleteResets("pb", "receipt-id");
    }

    @Test
    void deleteNonLastVersionLeavesSequencesAlone() {
        when(templates.findByVersion("pb", "receipt-id", 1))
                .thenReturn(Optional.of(row(1, request(null, null).config().normalized())));
        when(templates.countVersions("pb", "receipt-id")).thenReturn(3L);
        service.delete("pb", "receipt-id", "v1");
        verify(sequences, never()).dropSequence(anyString());
        verify(sequences, never()).deleteResets(anyString(), anyString());
    }

    @Test
    void deleteUnknownVersionIs404() {
        when(templates.findByVersion(anyString(), anyString(), anyInt()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete("pb", "receipt-id", "v9"))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
