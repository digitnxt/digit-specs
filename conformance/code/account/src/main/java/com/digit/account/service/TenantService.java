package com.digit.account.service;

import com.digit.account.clients.keycloak.KeycloakClient;
import com.digit.account.clients.notification.NotificationClient;
import com.digit.account.clients.otp.OtpClient;
import com.digit.account.config.AccountProperties;
import com.digit.account.enrichment.Enrichment;
import com.digit.account.model.Mappers;
import com.digit.account.model.TenantCreateRequest;
import com.digit.account.model.TenantEntity;
import com.digit.account.model.TenantListResponse;
import com.digit.account.model.TenantResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.digit.account.model.TenantUpdateRequest;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.digit.account.validator.TenantValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3 tenant lifecycle: admin create (DB-first, then Keycloak with DB rollback on failure, then
 * publish create + migration events), read/list/update/delete.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenantRepo;
    private final TenantConfigRepository configRepo;
    private final KeycloakClient keycloakClient;
    private final NotificationClient notificationClient;
    private final OtpClient otpClient;
    private final EventPublisher eventPublisher;
    private final AccountProperties props;
    private final ObjectMapper objectMapper;

    public TenantService(TenantRepository tenantRepo, TenantConfigRepository configRepo,
                         KeycloakClient keycloakClient, NotificationClient notificationClient,
                         OtpClient otpClient, EventPublisher eventPublisher,
                         AccountProperties props, ObjectMapper objectMapper) {
        this.tenantRepo = tenantRepo;
        this.configRepo = configRepo;
        this.keycloakClient = keycloakClient;
        this.notificationClient = notificationClient;
        this.otpClient = otpClient;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.objectMapper = objectMapper;

        List<String> loginUrls = props.getNotification().getFirstLoginUrls();
        if (loginUrls == null || loginUrls.isEmpty()) {
            log.warn("account.notification.first-login-urls is not set: the temp-password email will "
                    + "carry no sign-in link.");
        } else {
            // Warned per entry rather than for the list as a whole: one bad entry among several is
            // the likely mistake, and naming its position is what makes it findable.
            for (int i = 0; i < loginUrls.size(); i++) {
                String u = loginUrls.get(i);
                if (u == null || u.isBlank()) {
                    log.warn("account.notification.first-login-urls[{}] is blank; it will be skipped.", i);
                } else {
                    if (u.indexOf('=') <= 0) {
                        log.warn("account.notification.first-login-urls[{}] ({}) has no \"label=\" "
                                + "prefix. It is emailed under a positional key the template cannot "
                                + "name, so it will not appear in a labelled email body.", i, u);
                    }
                    if (!u.contains("{realm}") && !u.contains("{tenantCode}")) {
                        log.warn("account.notification.first-login-urls[{}] ({}) contains neither "
                                + "{{realm}} nor {{tenantCode}}, so every tenant admin is emailed the "
                                + "same link and only one of them can sign in with it.", i, u);
                    }
                }
            }
        }
    }

    public TenantRepository getRepo() {
        return tenantRepo;
    }

    /**
     * Cross-tenant guard for the id-addressed endpoints. The tenantId is optional: absent means a
     * platform caller operating across tenants, which is how these endpoints have always behaved.
     * When present it must name the row being addressed, so a caller scoped to one realm cannot
     * reach another tenant's row by guessing or replaying its id. It is compared against the row's
     * code because a tenant has no identifier separate from it. The message names the scope but not
     * the row's own code, which the caller is by definition not entitled to.
     */
    private static void requireTenantIdMatches(TenantEntity entity, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        if (!tenantId.equals(entity.getCode())) {
            throw new CustomException("FORBIDDEN", "Tenant " + entity.getId()
                    + " is outside the X-Tenant-Id scope " + tenantId, HttpStatus.FORBIDDEN);
        }
    }

    /** Mirrors Create. */
    public TenantResponse create(TenantCreateRequest req, String clientId, String requestId) {
        List<String> errs = TenantValidator.validateTenantCreateRequest(req);
        if (!errs.isEmpty()) {
            throw new CustomException("VALIDATION_ERROR", String.join("; ", errs));
        }

        // Resolved here rather than relying on the same defaulting inside enrichment: that method
        // takes the actor by value, so the "admin" fallback it applies reaches the entity's audit
        // fields but not this variable. Everything below passes clientId on to another service, and
        // the OTP config call sends X-User-Id only when it is non-blank — so leaving it empty had
        // the tenant created and then its OTP config rejected for a missing header.
        clientId = resolveActor(clientId);

        TenantEntity entity = Mappers.tenantCreateRequestToEntity(req);
        Enrichment.enrichTenantEntity(entity, clientId, requestId);
        // The code only exists after enrichment derives it from the name, so it cannot be checked
        // by the request validation above.
        List<String> codeErrs = TenantValidator.validateDerivedCode(entity.getCode(), entity.getName());
        if (!codeErrs.isEmpty()) {
            throw new CustomException("VALIDATION_ERROR", String.join("; ", codeErrs));
        }
        entity.setActive(true);

        // A caller-supplied password is left permanent and never emailed back — they already know
        // it. Only a generated one is forced to be changed at first login and mailed out, because
        // it is the only case where the admin has no other way to learn their credential.
        String password = req.getPassword();
        // isBlank, not isEmpty: whitespace is not a usable credential, so it counts as absent and
        // gets a generated one. Otherwise eight spaces would pass the minimum-length check and be
        // installed as a permanent password that is never emailed.
        boolean passwordGenerated = password == null || password.isBlank();
        if (passwordGenerated) {
            password = PasswordGenerator.generate();
            entity.setPasswordGenerated(true);
            // Only a generated password has a sign-in link, because the link exists to redeem the
            // credential we emailed. A caller who chose their own password was told nothing and has
            // nothing to redeem, so there is no link to record and the field stays absent.
            //
            // Recorded on the row rather than recomputed on read: this is the link that was actually
            // emailed, so a later change to the configured template must not rewrite it for tenants
            // provisioned before the change.
            entity.setFirstLoginUrls(firstLoginUrls(entity.getCode()));
        }

        // Phase 1: DB insert FIRST. A unique-constraint violation surfaces as a DUPLICATE_RECORD
        // CustomException (from PgErrors.translate); a database failure reaches
        // DatabaseExceptionHandler as a 500.
        try {
            tenantRepo.create(entity);
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("failed to create tenant {} in database", entity.getCode(), e);
            throw e;
        }

        // Phase 2: Keycloak realm. Failure rolls back the DB row and is a downstream error (502).
        try {
            keycloakClient.createRealmWithFullConfig(entity.getCode(), entity.getEmail(),
                    entity.getName(), password, entity.getPhone(), passwordGenerated);
        } catch (RuntimeException kcErr) {
            try {
                tenantRepo.delete(entity.getId());
            } catch (RuntimeException rollbackErr) {
                log.error("failed to roll back tenant {} after its Keycloak realm failed", entity.getCode(),
                        rollbackErr);
                throw keycloakFailure("failed to create Keycloak realm (removing the tenant row also "
                        + "failed - manual cleanup required)", entity.getCode(), kcErr);
            }
            throw keycloakFailure("failed to create Keycloak realm", entity.getCode(), kcErr);
        }

        // A tenant's OTP configs are seeded by the OTP service itself, off the same provisioning
        // event that creates its schema. Doing it from here raced that schema: the config write
        // usually arrived first and failed against a table that did not exist yet.

        // Deliberately best-effort: the tenant row and its realm already exist, so failing the
        // request here would mean destroying a provisioned realm over an email, and email is the
        // least reliable dependency in this path. Logged rather than swallowed because the password
        // is generated and stored nowhere — once this send fails it cannot be resent, and the only
        // way in is a Keycloak admin resetting the user's password for that realm.
        Boolean temporaryPasswordEmailed = null;
        if (passwordGenerated) {
            try {
                temporaryPasswordEmailed = notificationClient.sendTempPassword(entity.getCode(),
                        entity.getName(), entity.getEmail(), password,
                        entity.getFirstLoginUrls(), clientId);
            } catch (RuntimeException e) {
                temporaryPasswordEmailed = false;
                log.error("Tenant {} was created, but emailing its generated temporary password to "
                                + "{} failed. That password is not stored and cannot be resent: the "
                                + "admin cannot sign in until a Keycloak admin resets the password "
                                + "for that user in realm {}.",
                        entity.getCode(), entity.getEmail(), entity.getCode(), e);
            }
        }

        // Publish create event + migration event.
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("tenantId", entity.getId());
        eventData.put("tenantCode", entity.getCode());
        eventData.put("name", entity.getName());
        eventData.put("email", entity.getEmail());
        eventPublisher.publishEvent(props.getPubsub().getTopics().getCreateTenant(), "CREATE",
                entity.getCode(), clientId, eventData, 1);

        // Logged, never thrown: the tenant and its realm already exist, so failing the request here
        // would be worse than the lost event. But it must not be silent — every service with schema
        // separation on learns about the tenant from this one event, so losing it leaves the tenant
        // with no schema anywhere, recoverable only by calling POST /internal/migrate on each service.
        // The empty catch this replaces made that outcome indistinguishable from success.
        //
        // Note this cannot fire merely because pub/sub is switched off: publishRaw returns early via
        // shouldPublish() when there is no PubSubClient or account.pubsub.enabled is false. Reaching
        // here means a publish was attempted and the broker rejected it.
        String migrationTopic = props.getPubsub().getTopics().getMigrationTopic();
        Map<String, Object> migrationEvent = new HashMap<>();
        migrationEvent.put("tenantId", entity.getCode());
        try {
            eventPublisher.publishRaw(migrationTopic, objectMapper.writeValueAsBytes(migrationEvent));
        } catch (Exception e) {
            log.error("Tenant {} created, but publishing its migration event to {} failed. No service "
                            + "will create a schema for it until POST /internal/migrate is called on each.",
                    entity.getCode(), migrationTopic, e);
        }

        TenantResponse resp = Mappers.tenantFromEntity(entity);
        resp.setTemporaryPasswordEmailed(temporaryPasswordEmailed);
        return resp;
    }

    /**
     * The sign-in links for the temp-password email, from
     * {@code account.notification.first-login-urls} with {@code {realm}} / {@code {tenantCode}}
     * substituted in every entry. Order is preserved: the first entry is the primary link.
     *
     * <p>Blank entries are dropped rather than emailed as empty links, and a list that is entirely
     * blank yields null so the field is omitted altogether — an empty array in the response would
     * claim links were sent when none were.
     *
     * <p>The default first entry is the realm's admin console, served by the
     * {@code security-admin-console} client. That client's browser flow is overridden to Keycloak's
     * built-in one, so signing in there asks for a username and password rather than the realm's
     * default one-time code — which is what makes the emailed password usable at all. The
     * UPDATE_PASSWORD required action then forces a new one to be chosen.
     */
    private Map<String, String> firstLoginUrls(String tenantCode) {
        List<String> configured = props.getNotification().getFirstLoginUrls();
        if (configured == null || configured.isEmpty()) {
            return null;
        }
        // Ordered so the email lists destinations in the order they were configured, and keyed by
        // label so the template can address each one: the notification renderer does field access
        // only, with no loop, so an unlabelled list can only ever be printed as one blob.
        Map<String, String> out = new LinkedHashMap<>();
        int unlabelled = 0;
        for (String entry : configured) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int eq = entry.indexOf('=');
            String label;
            String url;
            if (eq > 0) {
                label = entry.substring(0, eq).strip();
                // First '=' only: a URL may carry more in its query string.
                url = entry.substring(eq + 1).strip();
            } else {
                // Kept rather than dropped, under a positional key, so a config written before
                // labels existed still delivers its links instead of silently losing them. The
                // template cannot name these, so the constructor warns about them.
                label = "link" + (++unlabelled);
                url = entry.strip();
            }
            if (label.isEmpty() || url.isEmpty()) {
                continue;
            }
            out.put(label, url.replace("{realm}", tenantCode).replace("{tenantCode}", tenantCode));
        }
        return out.isEmpty() ? null : out;
    }

    /** Mirrors Get — returns null when not found. */
    public TenantResponse get(String id) {
        TenantEntity e = tenantRepo.getById(id);
        return Mappers.tenantFromEntity(e);
    }

    /** Mirrors List. */
    public TenantListResponse list(String code, String name, String email, Boolean isActive,
                                   int page, int size) {
        TenantRepository.Page p = tenantRepo.list(code, name, email, isActive, page, size);
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        TenantListResponse resp = new TenantListResponse();
        resp.setTotalCount((int) p.total);
        resp.setPage(page);
        resp.setSize(size);
        resp.setHasMore((long) page * size < p.total);
        resp.setTenants(Mappers.tenantsFromEntities(p.rows));
        return resp;
    }

    /** Mirrors Update. */
    public TenantResponse update(String id, TenantUpdateRequest req, String clientId, String requestId) {
        return update(id, req, clientId, requestId, null);
    }

    public TenantResponse update(String id, TenantUpdateRequest req, String clientId, String requestId,
                                 String tenantId) {
        TenantEntity existing = tenantRepo.getById(id);
        if (existing == null) {
            throw new CustomException("NOT_FOUND", "Tenant not found", HttpStatus.NOT_FOUND);
        }
        requireTenantIdMatches(existing, tenantId);
        // version is optional: when sent it must match, checked before the realm flip below so a
        // stale request never touches Keycloak. Either way the compare-and-swap in tenantRepo.update
        // is against the version read here, closing the race with a concurrent write.
        if (req.getVersion() != null && req.getVersion() != existing.getVersion()) {
            throw versionMismatch();
        }
        TenantEntity updated = Mappers.tenantUpdateRequestToEntity(existing, req, clientId, requestId,
                System.currentTimeMillis());
        List<String> errs = TenantValidator.validateTenantEntity(updated);
        if (!errs.isEmpty()) {
            throw new CustomException("VALIDATION_ERROR", String.join("; ", errs));
        }

        // The realm flip goes before the DB write so a deactivation fails closed: if the write then
        // fails, the realm is restored below, and the worst case is a tenant still locked out with a
        // loud error rather than one the DB calls inactive while its users keep authenticating.
        boolean activeChanged = updated.isActive() != existing.isActive();
        if (activeChanged) {
            try {
                keycloakClient.setRealmEnabled(updated.getCode(), updated.isActive());
            } catch (RuntimeException e) {
                throw keycloakFailure("failed to " + (updated.isActive() ? "enable" : "disable")
                        + " Keycloak realm", updated.getCode(), e);
            }
        }

        try {
            if (!tenantRepo.update(updated, existing.getVersion())) {
                throw versionMismatch();
            }
        } catch (RuntimeException e) {
            if (activeChanged) {
                try {
                    keycloakClient.setRealmEnabled(existing.getCode(), existing.isActive());
                } catch (RuntimeException rollbackErr) {
                    log.error("failed to update tenant {} in database", existing.getCode(), e);
                    log.error("failed to roll tenant {}'s Keycloak realm back to enabled={}",
                            existing.getCode(), existing.isActive(), rollbackErr);
                    throw new CustomException("DATABASE_ERROR", "failed to update tenant "
                            + "(rolling the Keycloak realm back to enabled=" + existing.isActive()
                            + " also failed - manual cleanup required)", HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
            // A translated business failure keeps its own status; a database failure reaches
            // DatabaseExceptionHandler as a 500.
            if (!(e instanceof CustomException)) {
                log.error("failed to update tenant {} in database", existing.getCode(), e);
            }
            throw e;
        }

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("tenantId", updated.getId());
        eventData.put("tenantCode", updated.getCode());
        eventData.put("isActive", updated.isActive());
        eventPublisher.publishEvent(props.getPubsub().getTopics().getUpdateTenant(), "UPDATE",
                updated.getCode(), clientId, eventData, 1);

        return Mappers.tenantFromEntity(updated);
    }

    /** Mirrors DeleteByID. */
    public void deleteById(String id, String clientId) {
        deleteById(id, clientId, null);
    }

    public void deleteById(String id, String clientId, String tenantId) {
        TenantEntity entity;
        try {
            entity = tenantRepo.getById(id);
        } catch (RuntimeException e) {
            log.error("failed to get tenant {}", id, e);
            throw e;
        }
        if (entity == null) {
            throw new CustomException("NOT_FOUND", "Tenant not found", HttpStatus.NOT_FOUND);
        }
        requireTenantIdMatches(entity, tenantId);
        String tenantCode = entity.getCode();

        if (configRepo != null) {
            try {
                configRepo.deleteByTenant(tenantCode);
            } catch (RuntimeException e) {
                log.error("failed to delete tenant {}'s configs", tenantCode, e);
                throw e;
            }
        }

        try {
            keycloakClient.deleteRealm(tenantCode);
        } catch (RuntimeException e) {
            throw keycloakFailure("failed to delete Keycloak realm", tenantCode, e);
        }

        try {
            tenantRepo.delete(id);
        } catch (RuntimeException e) {
            log.error("failed to delete tenant {} from database", tenantCode, e);
            throw e;
        }

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("tenantId", entity.getId());
        eventData.put("tenantCode", tenantCode);
        eventPublisher.publishEvent(props.getPubsub().getTopics().getDeleteTenant(), "DELETE",
                tenantCode, resolveActor(clientId), eventData, 1);
    }

    private static CustomException versionMismatch() {
        return new CustomException("ROW_VERSION_MISMATCH", "Tenant was modified concurrently", HttpStatus.CONFLICT);
    }

    /** Logs a failed Keycloak step with its cause and returns the client-facing 502. */
    private static CustomException keycloakFailure(String message, String tenantCode, RuntimeException cause) {
        log.error("{} for tenant {}", message, tenantCode, cause);
        return new CustomException("DOWNSTREAM_ERROR", message, HttpStatus.BAD_GATEWAY);
    }

    /**
     * The actor to attribute a change to when the caller sent no identity. One value for every
     * path, matching what enrichment writes to an entity's audit fields, so a tenant's audit trail
     * and its events name the same actor.
     */
    private static String resolveActor(String clientId) {
        return (clientId == null || clientId.isEmpty()) ? "admin" : clientId;
    }
}
