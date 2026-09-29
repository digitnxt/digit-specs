package org.digit.idgen.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.digit.idgen.events.TemplateEventPublisher;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.model.ErrorCodes;
import org.digit.idgen.model.SequenceScope;
import org.digit.idgen.model.TemplateConfig;
import org.digit.idgen.model.TemplateRow;
import org.digit.idgen.repo.SequenceNames;
import org.digit.idgen.repo.SequenceRepository;
import org.digit.idgen.repo.TemplateRepository;
import org.digit.tracer.model.CustomException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Template lifecycle. Versions are immutable rows: POST creates v1 (+ the Postgres
 * sequence + lookup row), PUT inserts version n+1, DELETE removes one version and
 * cleans up sequences only when it was the last one.
 *
 * @Transactional replaces the request-wide transaction the Go service got from the
 * tenant-migration middleware — create/delete are multi-statement and must stay atomic.
 */
@Service
public class TemplateService {

    private final TemplateRepository templates;
    private final SequenceRepository sequences;
    private final TemplateEventPublisher events;
    private final Clock clock;

    public TemplateService(TemplateRepository templates, SequenceRepository sequences,
                           TemplateEventPublisher events, Clock clock) {
        this.templates = templates;
        this.sequences = sequences;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Dtos.TemplateResponse create(String tenantId, String userId, String requestId,
                                        Dtos.TemplateRequest request) {
        TemplateConfig config = request.config().normalized();
        TemplateRenderer.validate(config);

        if (templates.existsByCode(tenantId, request.templateCode())) {
            throw new CustomException(ErrorCodes.CONFLICT, "template already exists", HttpStatus.CONFLICT);
        }

        long now = clock.millis();
        TemplateRow row = new TemplateRow(UUID.randomUUID(), tenantId, request.templateCode(), 1,
                config, now, userId, now, userId, requestId);
        try {
            templates.insert(row);
        } catch (DuplicateKeyException e) {
            // concurrent POST won the exists-check race — UNIQUE(tenantid,templatecode,version)
            // fired; same answer as the sequential case
            throw new CustomException(ErrorCodes.CONFLICT, "template already exists", HttpStatus.CONFLICT);
        }

        String seqName = SequenceNames.of(tenantId, request.templateCode());
        sequences.createSequence(seqName, config.sequence().start());
        sequences.insertLookup(seqName, tenantId, request.templateCode(), requestId);

        Dtos.TemplateResponse response = row.toResponse();
        events.created(tenantId, userId, response);
        return response;
    }

    @Transactional
    public Dtos.TemplateResponse update(String tenantId, String userId, String requestId,
                                        Dtos.TemplateRequest request) {
        TemplateConfig config = request.config().normalized();
        TemplateRenderer.validate(config);

        TemplateRow latest = templates.findLatest(tenantId, request.templateCode())
                .orElseThrow(TemplateService::notFound);

        // GLOBAL start is baked into the Postgres sequence at creation and never resets;
        // a different start in a new version would be stored but silently inert.
        // Scoped starts apply on every future window reset — changes are allowed.
        TemplateConfig.Sequence latestSeq = latest.config().sequence();
        if (config.sequence().scope() == SequenceScope.GLOBAL && latestSeq != null
                && !Objects.equals(config.sequence().start(), latestSeq.start())) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST,
                    "invalid template config: cannot change start for GLOBAL-scoped templates (current value: "
                            + latestSeq.start() + ") — the Postgres sequence was initialised at template "
                            + "creation and will not reset; delete and recreate the template to use a "
                            + "different starting value",
                    HttpStatus.BAD_REQUEST);
        }

        TemplateRow row = new TemplateRow(UUID.randomUUID(), tenantId, latest.templateCode(),
                latest.version() + 1, config,
                latest.createdTime(), latest.createdBy(),
                clock.millis(), userId, requestId);
        try {
            templates.insert(row);
        } catch (DuplicateKeyException e) {
            // concurrent PUT inserted the same version+1 first (same mapping as create's race)
            throw new CustomException(ErrorCodes.CONFLICT,
                    "template version conflict — concurrent update, retry", HttpStatus.CONFLICT);
        }

        Dtos.TemplateResponse response = row.toResponse();
        events.updated(tenantId, userId, response);
        return response;
    }

    /**
     * Branch priority (exactly one runs, as in Go): ids → code+version → code (latest)
     * → all-latest paginated. No match is 200 with an empty list, never 404.
     */
    public List<Dtos.TemplateResponse> search(String tenantId, String templateCode, String version,
                                              List<UUID> ids, Integer limit, int offset) {
        if (version != null && templateCode == null) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST,
                    "invalid template config: version requires templateCode", HttpStatus.BAD_REQUEST);
        }
        // parsed before branching (as in Go): a malformed version is 400 even when
        // the ids branch would win
        Integer versionInt = version == null ? null : parseVersion(version);

        List<TemplateRow> rows;
        if (ids != null && !ids.isEmpty()) {
            rows = templates.findByIds(tenantId, ids);
        } else if (templateCode != null && versionInt != null) {
            rows = templates.findByVersion(tenantId, templateCode, versionInt)
                    .map(List::of).orElse(List.of());
        } else if (templateCode != null) {
            rows = templates.findLatest(tenantId, templateCode).map(List::of).orElse(List.of());
        } else {
            int effectiveLimit = (limit == null || limit <= 0) ? 25 : limit;
            rows = templates.findAllLatest(tenantId, effectiveLimit, offset);
        }
        return rows.stream().map(TemplateRow::toResponse).toList();
    }

    @Transactional
    public void delete(String tenantId, String templateCode, String version) {
        int versionInt = parseVersion(version);

        templates.findByVersion(tenantId, templateCode, versionInt)
                .orElseThrow(TemplateService::notFound);

        long total = templates.countVersions(tenantId, templateCode);
        templates.delete(tenantId, templateCode, versionInt);

        if (total == 1) {
            sequences.dropSequence(SequenceNames.of(tenantId, templateCode));
            sequences.deleteLookup(tenantId, templateCode);
            sequences.deleteResets(tenantId, templateCode);
        }

        events.deleted(tenantId, Map.of("templateCode", templateCode, "version", version));
    }

    public Optional<TemplateRow> findLatest(String tenantId, String templateCode) {
        return templates.findLatest(tenantId, templateCode);
    }

    /** "v3" → 3. Spec: ^v[1-9][0-9]*$ — lowercase v, no leading zeros, >= v1. */
    static int parseVersion(String v) {
        if (v.length() < 2 || v.charAt(0) != 'v') {
            throw invalidVersion(v);
        }
        long n = 0;
        for (int i = 1; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                throw invalidVersion(v);
            }
            n = n * 10 + (c - '0');
            // int overflow would alias huge versions onto real ones (v4294967297 → v1);
            // Go's 64-bit int just queried the huge value and 404'd — 400 here is safer
            if (n > Integer.MAX_VALUE) {
                throw invalidVersion(v);
            }
        }
        // Rejects both "v0" and leading-zero forms like "v01" (Go parses digits the same
        // way and its n==0 check only catches "v0"; leading zeros still parse — matching
        // Go exactly: "v01" parses to 1 there too, so it is accepted there as well.)
        if (n == 0) {
            throw new CustomException(ErrorCodes.INVALID_REQUEST,
                    "invalid template config: version must be >= v1, got \"" + v + "\"",
                    HttpStatus.BAD_REQUEST);
        }
        return (int) n;
    }

    private static CustomException invalidVersion(String v) {
        return new CustomException(ErrorCodes.INVALID_REQUEST,
                "invalid template config: version must match ^v[1-9][0-9]*$, got \"" + v + "\"",
                HttpStatus.BAD_REQUEST);
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCodes.NOT_FOUND, "template not found", HttpStatus.NOT_FOUND);
    }
}
