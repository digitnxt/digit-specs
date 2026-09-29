package org.digit.notify.app.service;

import com.github.f4b6a3.ulid.UlidCreator;
import org.digit.notify.app.dispatch.DispatchEngine;
import org.digit.notify.app.dispatch.RecipientAddresses;
import org.digit.notify.app.domain.entity.NotificationAttemptEntity;
import org.digit.notify.app.domain.entity.NotificationConfigEntity;
import org.digit.notify.app.domain.entity.NotificationLogEntity;
import org.digit.notify.app.domain.entity.ProviderEntity;
import org.digit.notify.app.domain.entity.ProviderMappingEntity;
import org.digit.notify.app.domain.repository.*;
import org.digit.notify.app.constants.ErrorCodes;
import org.digit.tracer.model.CustomException;
import org.digit.notify.app.model.ChannelDispatchStatus;
import org.digit.notify.app.model.NotifyRequest;
import org.digit.notify.app.model.NotifyResponse;
import org.digit.notify.app.dispatch.DispatchOutcome;
import org.digit.notify.spi.Channel;
import org.digit.notify.spi.DispatchStatus;
import org.digit.notify.spi.Recipient;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationConfigRepository configRepository;
    private final ProviderMappingRepository mappingRepository;
    private final ProviderRepository providerRepository;
    private final NotificationLogRepository logRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final DispatchEngine dispatchEngine;

    public NotificationService(
        NotificationConfigRepository configRepository,
        ProviderMappingRepository mappingRepository,
        ProviderRepository providerRepository,
        NotificationLogRepository logRepository,
        NotificationAttemptRepository attemptRepository,
        DispatchEngine dispatchEngine
    ) {
        this.configRepository = configRepository;
        this.mappingRepository = mappingRepository;
        this.providerRepository = providerRepository;
        this.logRepository = logRepository;
        this.attemptRepository = attemptRepository;
        this.dispatchEngine = dispatchEngine;
    }

    public NotifyResponse sendNotification(NotifyRequest request, String tenantId) {
        var config = configRepository
            .findByTenantIdAndTemplateCodeAndIsActiveTrue(tenantId, request.templateCode())
            .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND,
                "NotificationConfig not found with id: " + request.templateCode(), HttpStatus.NOT_FOUND));

        var outcome = dispatchEngine.dispatch(request, config, tenantId);

        String notificationId = "ntf_" + UlidCreator.getUlid().toString();

        var log = new NotificationLogEntity();
        log.setNotificationId(notificationId);
        log.setTenantId(tenantId);
        log.setTemplateCode(request.templateCode());
        log.setRecipientRef(resolveRecipientRef(request.recipient(), outcome));
        log.setCreatedAt(Instant.now());
        logRepository.save(log);

        var attemptEntities = outcome.attempts().stream().map(a -> {
            var entity = new NotificationAttemptEntity();
            entity.setNotificationId(notificationId);
            entity.setTenantId(tenantId);
            entity.setChannel(a.channel().name());
            entity.setProviderName(a.providerName());
            entity.setAttemptNo(a.attemptNo());
            entity.setStatus(a.status().name());
            entity.setReason(a.reason());
            entity.setAttemptedAt(a.attemptedAt());
            return entity;
        }).toList();
        attemptRepository.saveAll(attemptEntities);

        var channelStatuses = outcome.results().stream().map(r ->
            new ChannelDispatchStatus(r.channel().name(), r.status().name(), r.providerName(), r.reason())
        ).toList();

        return new NotifyResponse(notificationId, request.templateCode(), channelStatuses);
    }

    /**
     * The address this send actually reached, for looking a send up by who it went to.
     *
     * <p>Derived from the outcome rather than from the request, because the first address a caller
     * happens to supply is not necessarily one this send used. A request to an SMS-only template
     * carrying both a phone number and an email took the phone — recording the email would assert
     * contact at an address never touched, which is worse than recording none.
     *
     * <p>Iterates the channel enum rather than {@code outcome.results()}: results are filled by
     * parallel forks, so their order varies between runs and identical requests would log different
     * addresses.
     *
     * <p>Still one address for a send that may have used several. That is a deliberate limit — this
     * column is a search convenience, and per-channel addresses belong on notification_attempt,
     * which is the table with a row per channel.
     */
    /** Same message the DuplicateMappingException it replaces produced, including the null country. */
    private static CustomException duplicateMapping(String tenantId, String channel, @Nullable String country) {
        return new CustomException(ErrorCodes.CONFLICT,
            "ProviderMapping already exists for tenant '" + tenantId + "', channel '" + channel
                + "', country '" + country + "'", HttpStatus.CONFLICT);
    }

    private static CustomException validation(String message) {
        return new CustomException(ErrorCodes.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private String resolveRecipientRef(Recipient recipient, DispatchOutcome outcome) {
        var attempted = outcome.results().stream()
            .filter(r -> r.status() != DispatchStatus.SKIPPED)
            .map(r -> r.channel())
            .collect(java.util.stream.Collectors.toSet());

        for (Channel channel : Channel.values()) {
            if (attempted.contains(channel)) {
                String address = RecipientAddresses.loggableAddress(channel, recipient);
                if (address != null) return address;
            }
        }

        // Nothing was attempted, or the only channel attempted was push, whose device tokens are not
        // worth recording. Falls back to whatever the caller supplied so the row is still findable.
        if (RecipientAddresses.isPresent(recipient.email())) return recipient.email();
        if (RecipientAddresses.isPresent(recipient.phone())) return recipient.phone();
        return "unknown";
    }

    @Transactional
    public NotificationConfigEntity createConfig(NotificationConfigEntity entity, String tenantId) {
        configRepository.findByTenantIdAndTemplateCode(tenantId, entity.getTemplateCode())
            .ifPresent(existing -> {
                throw new CustomException(ErrorCodes.CONFLICT,
                    "NotificationConfig already exists for tenant '" + tenantId
                        + "' and templateCode '" + entity.getTemplateCode() + "'",
                    HttpStatus.CONFLICT);
            });
        entity.setTenantId(tenantId);
        entity.setActive(true);
        entity.getAuditDetail().setCreatedTime(Instant.now());
        return configRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public NotificationConfigEntity getConfig(UUID id, String tenantId) {
        return configRepository.findById(id)
            .filter(c -> c.getTenantId().equals(tenantId))
            .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND,
                "NotificationConfig not found with id: " + id, HttpStatus.NOT_FOUND));
    }

    /** Any combination of the filters, or none. A null filter is not applied. */
    @Transactional(readOnly = true)
    public List<NotificationConfigEntity> listConfigs(
        String tenantId, @Nullable String templateCode, @Nullable String tag, @Nullable Boolean isActive
    ) {
        return configRepository.search(tenantId, templateCode, tag, isActive);
    }

    @Transactional
    public NotificationConfigEntity updateConfig(UUID id, NotificationConfigEntity updated, String tenantId) {
        var existing = getConfig(id, tenantId);
        // templateCode is how every caller addresses this config, and nothing references it by id,
        // so renaming it silently breaks every sender still using the old code. Rejected rather
        // than ignored: a caller that asked for a rename needs to know it did not happen. Create
        // the new code and delete the old one to move a template.
        if (!existing.getTemplateCode().equals(updated.getTemplateCode())) {
            throw validation(
                "templateCode cannot be changed: this config is \"" + existing.getTemplateCode()
                + "\" and the request sent \"" + updated.getTemplateCode() + "\". Create a config "
                + "with the new code and delete this one instead.");
        }
        existing.setChannels(updated.getChannels());
        existing.setTag(updated.getTag());
        // isActive is deliberately not touched. The request carries no such field, so the mapper
        // fills it with a constant true, and copying that across turned every edit into a
        // reactivation — a config switched off in the database came back on after an unrelated
        // change to its wording. An update edits content; it does not decide lifecycle.
        existing.getAuditDetail().setLastModifiedTime(Instant.now());
        return configRepository.save(existing);
    }

    @Transactional
    public void deleteConfig(UUID id, String tenantId) {
        configRepository.delete(getConfig(id, tenantId));
    }

    @Transactional
    public ProviderMappingEntity createMapping(ProviderMappingEntity entity, String tenantId) {
        if (entity.getCountry() != null) {
            mappingRepository.findByTenantIdAndChannelAndCountry(
                tenantId, entity.getChannel(), entity.getCountry()
            ).ifPresent(e -> {
                throw duplicateMapping(tenantId, entity.getChannel(), entity.getCountry());
            });
        } else {
            mappingRepository.findByTenantIdAndChannelAndCountryIsNull(
                tenantId, entity.getChannel()
            ).ifPresent(e -> {
                throw duplicateMapping(tenantId, entity.getChannel(), null);
            });
        }

        entity.getProviders().forEach(name ->
            providerRepository.findByProviderName(name).orElseThrow(() ->
                validation("Provider '" + name + "' not found in registry"))
        );

        entity.setTenantId(tenantId);
        entity.getAuditDetail().setCreatedTime(Instant.now());
        return mappingRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ProviderMappingEntity> listMappings(String tenantId, @Nullable String channel) {
        if (channel != null) {
            return mappingRepository.findByTenantIdAndChannel(tenantId, channel);
        }
        return mappingRepository.findByTenantId(tenantId);
    }

    @Transactional
    public ProviderMappingEntity updateMapping(UUID id, ProviderMappingEntity updated, String tenantId) {
        var existing = mappingRepository.findById(id)
            .filter(m -> m.getTenantId().equals(tenantId))
            .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND,
                "ProviderMapping not found with id: " + id, HttpStatus.NOT_FOUND));
        updated.getProviders().forEach(name ->
            providerRepository.findByProviderName(name).orElseThrow(() ->
                validation("Provider '" + name + "' not found in registry"))
        );
        existing.setProviders(updated.getProviders());
        existing.getAuditDetail().setLastModifiedTime(Instant.now());
        return mappingRepository.save(existing);
    }

    @Transactional
    public void deleteMapping(UUID id, String tenantId) {
        var existing = mappingRepository.findById(id)
            .filter(m -> m.getTenantId().equals(tenantId))
            .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND,
                "ProviderMapping not found with id: " + id, HttpStatus.NOT_FOUND));
        mappingRepository.delete(existing);
    }

    @Transactional(readOnly = true)
    public List<ProviderEntity> listProviders(@Nullable String channel, @Nullable Boolean isActive) {
        if (channel != null && isActive != null) {
            return providerRepository.findByChannelAndIsActive(channel, isActive);
        }
        if (channel != null) {
            return providerRepository.findByChannel(channel);
        }
        if (isActive != null) {
            return providerRepository.findByIsActive(isActive);
        }
        return providerRepository.findAll();
    }

    @Transactional
    public ProviderEntity updateProviderStatus(UUID id, boolean isActive) {
        var entity = providerRepository.findById(id)
            .orElseThrow(() -> new CustomException(ErrorCodes.NOT_FOUND,
                "Provider not found with id: " + id, HttpStatus.NOT_FOUND));
        entity.setActive(isActive);
        entity.getAuditDetail().setLastModifiedTime(Instant.now());
        return providerRepository.save(entity);
    }
}
