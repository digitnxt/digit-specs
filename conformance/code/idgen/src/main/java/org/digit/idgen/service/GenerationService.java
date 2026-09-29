package org.digit.idgen.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.model.ErrorCodes;
import org.digit.idgen.model.SequenceScope;
import org.digit.idgen.model.TemplateRow;
import org.digit.idgen.repo.SequenceNames;
import org.digit.idgen.repo.SequenceRepository;
import org.digit.idgen.repo.TemplateRepository;
import org.digit.tracer.model.CustomException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ID generation from the latest template version. Not wrapped in a transaction of its
 * own: every sequence allocation is a single atomic statement. A render failure after a
 * scoped reservation leaves a counter gap — gaps are already tolerated (GLOBAL nextval
 * has them by nature).
 *
 * <p>Allocation used to retry 5× with a 200ms backoff. That was dropped when
 * tenant-migration was adopted, because its filter wraps every request in a transaction
 * and the retry could no longer do the job it existed for. The loop was there to ride
 * out a briefly unreachable database, and under a request-wide transaction that case is
 * handled earlier and better: if a connection cannot be acquired the filter fails the
 * request at transaction-open with a clean 500, never reaching this class. If the
 * connection dies mid-request, every retry would reuse that same dead connection (the
 * transaction pins it), and the filter's commit would fail regardless — so retrying only
 * bought an extra 800ms of latency, while holding a transaction and any row locks open,
 * before failing anyway. Callers retry the request instead.
 */
@Service
public class GenerationService {

    private final TemplateRepository templates;
    private final SequenceRepository sequences;
    private final MeterRegistry meters;
    private final Clock clock;

    public GenerationService(TemplateRepository templates, SequenceRepository sequences,
                             MeterRegistry meters, Clock clock) {
        this.templates = templates;
        this.sequences = sequences;
        this.meters = meters;
        this.clock = clock;
    }

    public Dtos.GenerateResponse generate(String tenantId, Dtos.GenerateRequest request) {
        TemplateRow tmpl = latestOr404(tenantId, request.templateCode());
        guardConfig(tmpl);
        tmpl = tmpl.withNormalizedConfig();

        LocalDate today = LocalDate.now(clock);
        Long seqValue = usesSeq(tmpl) ? allocate(tmpl, 1, today, false).getFirst() : null;
        String id = renderOne(tmpl, request.variables(), today, seqValue);

        recordGenerated(tmpl, tenantId, 1);
        return new Dtos.GenerateResponse(tmpl.templateCode(), tmpl.versionString(), id);
    }

    public Dtos.BulkGenerateResponse bulkGenerate(String tenantId, Dtos.BulkGenerateRequest request) {
        TemplateRow tmpl = latestOr404(tenantId, request.templateCode());
        guardConfig(tmpl);
        tmpl = tmpl.withNormalizedConfig();

        int count = request.count();
        LocalDate today = LocalDate.now(clock);
        List<Long> seqValues = usesSeq(tmpl) ? allocate(tmpl, count, today, true) : null;

        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(renderOne(tmpl, request.variables(), today, seqValues == null ? null : seqValues.get(i)));
        }

        recordGenerated(tmpl, tenantId, count);
        return new Dtos.BulkGenerateResponse(tmpl.templateCode(), tmpl.versionString(), count, ids);
    }

    private String renderOne(TemplateRow tmpl, Map<String, String> variables,
                             LocalDate today, Long seqValue) {
        String padded = seqValue == null ? ""
                : TemplateRenderer.formatSequence(seqValue, tmpl.config().sequence().padding());
        String rand = TemplateRenderer.usesToken(tmpl.config().template(), "RAND")
                ? TemplateRenderer.randomString(tmpl.config().random()) : "";
        return TemplateRenderer.render(tmpl.config().template(), padded, rand, variables, today);
    }

    private boolean usesSeq(TemplateRow tmpl) {
        return TemplateRenderer.usesToken(tmpl.config().template(), "SEQ");
    }

    private List<Long> allocate(TemplateRow tmpl, int count, LocalDate today, boolean bulk) {
        var seq = tmpl.config().sequence();
        try {
            if (seq.scope() == SequenceScope.GLOBAL) {
                String name = SequenceNames.of(tmpl.tenantId(), tmpl.templateCode());
                return count == 1 ? List.of(sequences.nextValue(name)) : sequences.nextValues(name, count);
            }
            long first = sequences.reserveScoped(tmpl.tenantId(), tmpl.templateCode(),
                    SequenceRepository.scopeKey(seq.scope(), today), seq.start(), count);
            List<Long> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(first + i);
            }
            return values;
        } catch (DataAccessException e) {
            // Mapped rather than propagated: an allocation failure stays a 422, the same
            // status the retry-exhaustion path used to return, so the API contract is
            // unchanged even though the retry itself is gone.
            throw new CustomException(ErrorCodes.UNPROCESSABLE,
                    "id generation failed: " + (bulk ? "bulk nextval" : "nextval")
                            + " failed: " + e.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private TemplateRow latestOr404(String tenantId, String templateCode) {
        return templates.findLatest(tenantId, templateCode)
                .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND,
                        "template not found", HttpStatus.NOT_FOUND));
    }

    /** Legacy rows persisted before defaults existed could have null blocks (as in Go). */
    private static void guardConfig(TemplateRow tmpl) {
        if (tmpl.config().sequence() == null) {
            throw unprocessable("template \"" + tmpl.templateCode() + "\" has no sequence config");
        }
        if (tmpl.config().random() == null) {
            throw unprocessable("template \"" + tmpl.templateCode() + "\" has no random config");
        }
    }

    private static CustomException unprocessable(String detail) {
        return new CustomException(ErrorCodes.UNPROCESSABLE,
                "id generation failed: " + detail, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private void recordGenerated(TemplateRow tmpl, String tenantId, int count) {
        // tag keys match the Go metric surface (id_type/tenantId) — existing PromQL keeps working
        meters.counter("ids_generated", "id_type", tmpl.templateCode(), "tenantId", tenantId)
                .increment(count);
    }
}
