package org.digit.notify.app.service;

import org.digit.notify.app.dispatch.DispatchEngine;
import org.digit.notify.app.dispatch.DispatchOutcome;
import org.digit.notify.app.dispatch.AttemptRecord;
import org.digit.notify.app.domain.entity.NotificationConfigEntity;
import org.digit.notify.app.domain.entity.NotificationLogEntity;
import org.digit.notify.app.domain.entity.ProviderMappingEntity;
import org.digit.notify.app.domain.repository.*;
import org.digit.notify.app.constants.ErrorCodes;
import org.digit.tracer.model.CustomException;
import org.digit.notify.app.model.NotifyRequest;
import org.digit.notify.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationConfigRepository configRepository;
    @Mock ProviderMappingRepository mappingRepository;
    @Mock ProviderRepository providerRepository;
    @Mock NotificationLogRepository logRepository;
    @Mock NotificationAttemptRepository attemptRepository;
    @Mock DispatchEngine dispatchEngine;

    @InjectMocks NotificationService service;

    private NotifyRequest sampleRequest() {
        var recipient = new Recipient("+911234567890", null, List.of(), "IN", Map.of());
        return new NotifyRequest("TMPL_001", recipient, Map.of(), null, Map.of());
    }

    @Test
    void sendNotification_happyPath_returnsResponseWithNtfPrefix() {
        var config = new NotificationConfigEntity();
        when(configRepository.findByTenantIdAndTemplateCodeAndIsActiveTrue("t1", "TMPL_001"))
            .thenReturn(Optional.of(config));

        var results = List.of(
            DispatchResult.dispatched(Channel.SMS, "twilio"),
            DispatchResult.dispatched(Channel.EMAIL, "sendgrid"),
            DispatchResult.skipped(Channel.WHATSAPP, "disabled"),
            DispatchResult.skipped(Channel.PUSH, "disabled")
        );
        var attempts = List.of(
            new AttemptRecord(Channel.SMS, "twilio", 1, DispatchStatus.DISPATCHED, null, Instant.now()),
            new AttemptRecord(Channel.EMAIL, "sendgrid", 1, DispatchStatus.DISPATCHED, null, Instant.now())
        );
        when(dispatchEngine.dispatch(any(), any(), any()))
            .thenReturn(new DispatchOutcome(results, attempts));
        when(logRepository.save(any())).thenReturn(null);
        when(attemptRepository.saveAll(any())).thenReturn(List.of());

        var response = service.sendNotification(sampleRequest(), "t1");

        assertThat(response.notificationId()).startsWith("ntf_");
        assertThat(response.channels()).hasSize(4);
        verify(logRepository, times(1)).save(any());
        verify(attemptRepository, times(1)).saveAll(argThat(list ->
            ((List<?>) list).size() == 2));
    }

    /**
     * Sends the given request and returns the recipient_ref that was written to notification_log.
     * Every channel but the listed ones is skipped, which is how a caller expresses "this send only
     * used these".
     */
    private String loggedRecipientRef(NotifyRequest request, Channel... dispatched) {
        when(configRepository.findByTenantIdAndTemplateCodeAndIsActiveTrue("t1", "TMPL_001"))
            .thenReturn(Optional.of(new NotificationConfigEntity()));

        var sent = List.of(dispatched);
        var results = java.util.Arrays.stream(Channel.values())
            .map(c -> sent.contains(c)
                ? DispatchResult.dispatched(c, "p")
                : DispatchResult.skipped(c, "not configured"))
            .toList();
        var attempts = sent.stream()
            .map(c -> new AttemptRecord(c, "p", 1, DispatchStatus.DISPATCHED, null, Instant.now()))
            .toList();
        when(dispatchEngine.dispatch(any(), any(), any()))
            .thenReturn(new DispatchOutcome(results, attempts));

        service.sendNotification(request, "t1");

        ArgumentCaptor<NotificationLogEntity> captor =
            ArgumentCaptor.forClass(NotificationLogEntity.class);
        verify(logRepository).save(captor.capture());
        return captor.getValue().getRecipientRef();
    }

    private static NotifyRequest requestTo(String phone, String email, List<String> tokens) {
        return new NotifyRequest("TMPL_001",
            new Recipient(phone, email, tokens, "IN", Map.of()), Map.of(), null, Map.of());
    }

    /**
     * The case this replaced: an SMS-only template with both addresses supplied used the phone, and
     * the old first-non-null rule recorded the email — contact asserted at an address never touched.
     */
    @Test
    void recipientRefIsTheAddressTheSendActuallyUsed() {
        var both = requestTo("+911234567890", "asha@example.com", List.of());

        assertThat(loggedRecipientRef(both, Channel.SMS)).isEqualTo("+911234567890");
    }

    @Test
    void recipientRefIsTheEmailWhenEmailIsWhatDispatched() {
        var both = requestTo("+911234567890", "asha@example.com", List.of());

        assertThat(loggedRecipientRef(both, Channel.EMAIL)).isEqualTo("asha@example.com");
    }

    /** Blank is absent, the same way the dispatch precheck and every provider guard treat it. */
    @Test
    void aBlankAddressIsNotRecorded() {
        var blankEmail = requestTo("+911234567890", "  ", List.of());

        assertThat(loggedRecipientRef(blankEmail, Channel.SMS)).isEqualTo("+911234567890");
    }

    /** A device token names no person and is not worth recording, so a push-only send has no ref. */
    @Test
    void aPushOnlySendHasNoRecordableAddress() {
        var pushOnly = requestTo(null, null, List.of("device-token-1"));

        assertThat(loggedRecipientRef(pushOnly, Channel.PUSH)).isEqualTo("unknown");
    }

    /** Every channel skipped: nothing was used, so the row falls back to what the caller supplied. */
    @Test
    void whenNothingWasAttemptedTheRefFallsBackToWhatWasSupplied() {
        var both = requestTo("+911234567890", "asha@example.com", List.of());

        assertThat(loggedRecipientRef(both)).isEqualTo("asha@example.com");
    }

    @Test
    void sendNotification_configNotFound_throwsEntityNotFoundException() {
        when(configRepository.findByTenantIdAndTemplateCodeAndIsActiveTrue("t1", "TMPL_001"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendNotification(sampleRequest(), "t1"))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getCode()).isEqualTo(ErrorCodes.NOT_FOUND);
                assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            });
        verify(dispatchEngine, never()).dispatch(any(), any(), any());
    }

    @Test
    void createMapping_duplicateCountrySpecific_throwsDuplicateMappingException() {
        var existing = new ProviderMappingEntity();
        when(mappingRepository.findByTenantIdAndChannelAndCountry("t1", "SMS", "IN"))
            .thenReturn(Optional.of(existing));

        var entity = new ProviderMappingEntity();
        entity.setChannel("SMS");
        entity.setCountry("IN");
        entity.setProviders(List.of("twilio"));

        assertThatThrownBy(() -> service.createMapping(entity, "t1"))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT);
                assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            });
    }

    @Test
    void createMapping_unknownProviderName_throwsValidationException() {
        when(mappingRepository.findByTenantIdAndChannelAndCountry(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(providerRepository.findByProviderName("unknown-provider"))
            .thenReturn(Optional.empty());

        var entity = new ProviderMappingEntity();
        entity.setChannel("SMS");
        entity.setCountry("IN");
        entity.setProviders(List.of("unknown-provider"));

        assertThatThrownBy(() -> service.createMapping(entity, "t1"))
            .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getCode()).isEqualTo(ErrorCodes.BAD_REQUEST);
                assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            })
            .hasMessageContaining("unknown-provider");
    }

    // ---- config update and search ----

    /**
     * The request body carries no isActive, so the mapper fills it with a constant true. Copying
     * that onto the stored config made every edit a reactivation; an update must leave the flag be.
     */
    @Test
    void updateConfigLeavesADeactivatedConfigDeactivated() {
        var id = UUID.randomUUID();
        var existing = new NotificationConfigEntity();
        existing.setId(id);
        existing.setTenantId("t1");
        existing.setTemplateCode("otp-login");
        existing.setActive(false);
        when(configRepository.findById(id)).thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var incoming = new NotificationConfigEntity();
        incoming.setTemplateCode("otp-login");
        incoming.setActive(true);                       // what the mapper always supplies

        var saved = service.updateConfig(id, incoming, "t1");

        assertThat(saved.isActive()).isFalse();
    }

    @Test
    void updateConfigStoresTheTag() {
        var id = UUID.randomUUID();
        var existing = new NotificationConfigEntity();
        existing.setId(id);
        existing.setTenantId("t1");
        existing.setTemplateCode("otp-login");
        when(configRepository.findById(id)).thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var incoming = new NotificationConfigEntity();
        incoming.setTemplateCode("otp-login");
        incoming.setTag("auth");

        assertThat(service.updateConfig(id, incoming, "t1").getTag()).isEqualTo("auth");
    }

    /** Filters compose: the chain this replaced applied only the first one supplied. */
    @Test
    void listConfigsPassesEveryFilterThrough() {
        when(configRepository.search("t1", "otp-login", "auth", true)).thenReturn(List.of());

        service.listConfigs("t1", "otp-login", "auth", true);

        verify(configRepository).search("t1", "otp-login", "auth", true);
    }

    @Test
    void listConfigsWithNoFiltersAppliesNone() {
        when(configRepository.search("t1", null, null, null)).thenReturn(List.of());

        service.listConfigs("t1", null, null, null);

        verify(configRepository).search("t1", null, null, null);
    }

    /**
     * A rename is refused, not ignored. Callers address a config by templateCode and nothing
     * references it by id, so a silent rename would break every sender still using the old code.
     */
    @Test
    void updateConfigRejectsAChangedTemplateCode() {
        var id = UUID.randomUUID();
        var existing = new NotificationConfigEntity();
        existing.setId(id);
        existing.setTenantId("t1");
        existing.setTemplateCode("otp-login");
        when(configRepository.findById(id)).thenReturn(Optional.of(existing));

        var incoming = new NotificationConfigEntity();
        incoming.setTemplateCode("otp-login-renamed");

        assertThatThrownBy(() -> service.updateConfig(id, incoming, "t1"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                assertThat(e.getCode()).isEqualTo(ErrorCodes.BAD_REQUEST);
                assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            })
                .hasMessageContaining("templateCode cannot be changed")
                .hasMessageContaining("otp-login")
                .hasMessageContaining("otp-login-renamed");

        verify(configRepository, never()).save(any());
    }
}
