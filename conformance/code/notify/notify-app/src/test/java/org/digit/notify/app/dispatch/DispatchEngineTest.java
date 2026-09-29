package org.digit.notify.app.dispatch;

import org.digit.notify.app.domain.entity.NotificationConfigEntity;
import org.digit.notify.app.domain.entity.config.ChannelConfig;
import org.digit.notify.app.domain.entity.config.ChannelsConfig;
import org.digit.notify.app.model.NotifyRequest;
import org.digit.notify.app.plugin.ProviderPluginLoader;
import org.digit.notify.app.template.TemplateRenderException;
import org.digit.notify.app.template.TemplateRenderer;
import org.digit.notify.spi.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchEngineTest {

    @Mock ProviderMappingResolver mappingResolver;
    @Mock ProviderPluginLoader pluginLoader;
    @Mock TemplateRenderer templateRenderer;
    @Mock NotificationChannelProvider mockProvider;
    @Mock NotificationChannelProvider fallbackProvider;

    DispatchEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DispatchEngine(mappingResolver, pluginLoader, templateRenderer);
    }

    private NotificationConfigEntity configWithAllChannelsEnabled() {
        var config = new ChannelConfig();
        config.setEnabled(true);
        config.setBody(Map.of("default", "Hello"));

        var channels = new ChannelsConfig();
        channels.setSms(config);
        channels.setWhatsapp(config);

        var emailConfig = new org.digit.notify.app.domain.entity.config.EmailChannelConfig();
        emailConfig.setEnabled(true);
        emailConfig.setBody(Map.of("default", "Hello"));
        emailConfig.setSubject(Map.of("default", "Subject"));
        channels.setEmail(emailConfig);

        var pushConfig = new org.digit.notify.app.domain.entity.config.PushChannelConfig();
        pushConfig.setEnabled(true);
        pushConfig.setBody(Map.of("default", "Hello"));
        pushConfig.setTitle(Map.of("default", "Title"));
        channels.setPush(pushConfig);

        var entity = new NotificationConfigEntity();
        entity.setChannels(channels);
        return entity;
    }

    /** Addressable on every channel — a device token included, or PUSH is rightly skipped. */
    private NotifyRequest sampleRequest() {
        var recipient = new Recipient("+911234567890", "test@example.com",
            List.of("device-token-1"), "IN", Map.of());
        return new NotifyRequest("TMPL_001", recipient, Map.of(), null, Map.of());
    }

    private NotifyRequest requestFor(Recipient recipient) {
        return new NotifyRequest("TMPL_001", recipient, Map.of(), null, Map.of());
    }

    @Test
    void allChannelsEnabled_allProvidersSucceed_returns4Results() {
        var config = configWithAllChannelsEnabled();
        var request = sampleRequest();

        when(templateRenderer.render(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new ChannelMessage(Channel.SMS, "Hello", null, null, Map.of()));

        when(mappingResolver.resolve(any(), any(), any()))
            .thenReturn(List.of("mock-provider"));
        when(pluginLoader.getProvidersOrdered(any(), any()))
            .thenReturn(List.of(mockProvider));
        when(mockProvider.providerName()).thenReturn("mock-provider");
        when(mockProvider.send(any(), any(), any()))
            .thenAnswer(inv -> DispatchResult.dispatched(
                ((ChannelMessage) inv.getArgument(0)).channel(), "mock-provider"));

        var outcome = engine.dispatch(request, config, "t1");

        assertThat(outcome.results()).hasSize(4);
        assertThat(outcome.results()).allMatch(r -> r.status() == DispatchStatus.DISPATCHED);
        assertThat(outcome.attempts()).hasSize(4);
    }

    /**
     * The case the per-channel template codes exist to work around: a config allows SMS, the caller
     * supplied no phone number. Without the precheck every SMS provider is called and refuses, so
     * one deliberate omission reads as a chain of failures.
     */
    @Test
    void channelWithNoAddressOnTheRecipient_isSkippedNotFailed() {
        var config = configWithAllChannelsEnabled();
        var emailOnly = new Recipient(null, "test@example.com", List.of(), "IN", Map.of());

        when(templateRenderer.render(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new ChannelMessage(Channel.EMAIL, "Hello", "Subject", null, Map.of()));
        when(mappingResolver.resolve(any(), any(), any())).thenReturn(List.of("mock-provider"));
        when(pluginLoader.getProvidersOrdered(any(), any())).thenReturn(List.of(mockProvider));
        when(mockProvider.providerName()).thenReturn("mock-provider");
        when(mockProvider.send(any(), any(), any()))
            .thenAnswer(inv -> DispatchResult.dispatched(
                ((ChannelMessage) inv.getArgument(0)).channel(), "mock-provider"));

        var outcome = engine.dispatch(requestFor(emailOnly), config, "t1");

        assertThat(outcome.results())
            .filteredOn(r -> r.channel() == Channel.EMAIL)
            .allMatch(r -> r.status() == DispatchStatus.DISPATCHED);
        assertThat(outcome.results())
            .filteredOn(r -> r.channel() != Channel.EMAIL)
            .allMatch(r -> r.status() == DispatchStatus.SKIPPED);

        // The expensive half of the old behaviour: no provider was called and no row was written
        // for a channel that was never addressable.
        assertThat(outcome.attempts()).hasSize(1);
        assertThat(outcome.attempts().getFirst().channel()).isEqualTo(Channel.EMAIL);
    }

    /** WhatsApp addresses by phone number, so a phone is enough for both it and SMS. */
    @Test
    void aPhoneNumberAddressesBothSmsAndWhatsapp() {
        var config = configWithAllChannelsEnabled();
        var phoneOnly = new Recipient("+911234567890", null, List.of(), "IN", Map.of());

        // Renders for whichever channel it is asked about — the provider stub reports the message's
        // channel back, so a fixed one would make every dispatch look like an SMS.
        when(templateRenderer.render(any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> new ChannelMessage(inv.getArgument(0), "Hello", null, null, Map.of()));
        when(mappingResolver.resolve(any(), any(), any())).thenReturn(List.of("mock-provider"));
        when(pluginLoader.getProvidersOrdered(any(), any())).thenReturn(List.of(mockProvider));
        when(mockProvider.providerName()).thenReturn("mock-provider");
        when(mockProvider.send(any(), any(), any()))
            .thenAnswer(inv -> DispatchResult.dispatched(
                ((ChannelMessage) inv.getArgument(0)).channel(), "mock-provider"));

        var outcome = engine.dispatch(requestFor(phoneOnly), config, "t1");

        assertThat(outcome.results())
            .filteredOn(r -> r.status() == DispatchStatus.DISPATCHED)
            .extracting(DispatchResult::channel)
            .containsExactlyInAnyOrder(Channel.SMS, Channel.WHATSAPP);
    }

    /** Blank counts as absent, the same way every provider's own guard treats it. */
    @Test
    void aBlankAddressIsTreatedAsMissing() {
        var config = configWithAllChannelsEnabled();
        var blank = new Recipient("   ", "", List.of(), "IN", Map.of());

        var outcome = engine.dispatch(requestFor(blank), config, "t1");

        assertThat(outcome.results()).hasSize(4);
        assertThat(outcome.results()).allMatch(r -> r.status() == DispatchStatus.SKIPPED);
        assertThat(outcome.attempts()).isEmpty();
    }

    @Test
    void channelDisabled_returnsSkippedForThatChannel() {
        var smsDisabled = new ChannelConfig();
        smsDisabled.setEnabled(false);

        var smsEnabled = new ChannelConfig();
        smsEnabled.setEnabled(true);
        smsEnabled.setBody(Map.of("default", "Hello"));

        var channels = new ChannelsConfig();
        channels.setSms(smsDisabled);
        channels.setWhatsapp(smsEnabled);
        channels.setEmail(null);
        channels.setPush(null);

        var entity = new NotificationConfigEntity();
        entity.setChannels(channels);

        when(templateRenderer.render(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new ChannelMessage(Channel.WHATSAPP, "Hello", null, null, Map.of()));
        when(mappingResolver.resolve(eq(Channel.WHATSAPP), any(), any()))
            .thenReturn(List.of("mock-provider"));
        when(pluginLoader.getProvidersOrdered(eq(Channel.WHATSAPP), any()))
            .thenReturn(List.of(mockProvider));
        when(mockProvider.providerName()).thenReturn("mock-provider");
        when(mockProvider.send(any(), any(), any()))
            .thenReturn(DispatchResult.dispatched(Channel.WHATSAPP, "mock-provider"));

        var outcome = engine.dispatch(sampleRequest(), entity, "t1");

        var smsResult = outcome.results().stream()
            .filter(r -> r.channel() == Channel.SMS).findFirst().orElseThrow();
        assertThat(smsResult.status()).isEqualTo(DispatchStatus.SKIPPED);

        assertThat(outcome.attempts().stream()
            .filter(a -> a.channel() == Channel.SMS)).isEmpty();
    }

    @Test
    void primaryProviderFails_fallbackSucceeds_returnsDispatched() {
        var smsConfig = new ChannelConfig();
        smsConfig.setEnabled(true);
        smsConfig.setBody(Map.of("default", "Hello"));

        var channels = new ChannelsConfig();
        channels.setSms(smsConfig);

        var entity = new NotificationConfigEntity();
        entity.setChannels(channels);

        when(templateRenderer.render(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new ChannelMessage(Channel.SMS, "Hello", null, null, Map.of()));
        when(mappingResolver.resolve(eq(Channel.SMS), any(), any()))
            .thenReturn(List.of("primary", "fallback"));
        when(pluginLoader.getProvidersOrdered(eq(Channel.SMS), any()))
            .thenReturn(List.of(mockProvider, fallbackProvider));

        when(mockProvider.providerName()).thenReturn("primary");
        when(fallbackProvider.providerName()).thenReturn("fallback");
        when(mockProvider.send(any(), any(), any()))
            .thenReturn(DispatchResult.failed(Channel.SMS, "primary", "timeout"));
        when(fallbackProvider.send(any(), any(), any()))
            .thenReturn(DispatchResult.dispatched(Channel.SMS, "fallback"));

        var outcome = engine.dispatch(sampleRequest(), entity, "t1");

        var smsResult = outcome.results().stream()
            .filter(r -> r.channel() == Channel.SMS).findFirst().orElseThrow();
        assertThat(smsResult.status()).isEqualTo(DispatchStatus.DISPATCHED);
        assertThat(smsResult.providerName()).isEqualTo("fallback");

        var smsAttempts = outcome.attempts().stream()
            .filter(a -> a.channel() == Channel.SMS).toList();
        assertThat(smsAttempts).hasSize(2);
        assertThat(smsAttempts.get(0).status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(smsAttempts.get(1).status()).isEqualTo(DispatchStatus.DISPATCHED);
    }

    @Test
    void allProvidersFailForChannel_returnsFailedResult() {
        var smsConfig = new ChannelConfig();
        smsConfig.setEnabled(true);
        smsConfig.setBody(Map.of("default", "Hello"));

        var channels = new ChannelsConfig();
        channels.setSms(smsConfig);

        var entity = new NotificationConfigEntity();
        entity.setChannels(channels);

        when(templateRenderer.render(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new ChannelMessage(Channel.SMS, "Hello", null, null, Map.of()));
        when(mappingResolver.resolve(eq(Channel.SMS), any(), any()))
            .thenReturn(List.of("p1"));
        when(pluginLoader.getProvidersOrdered(eq(Channel.SMS), any()))
            .thenReturn(List.of(mockProvider));

        when(mockProvider.providerName()).thenReturn("p1");
        when(mockProvider.send(any(), any(), any()))
            .thenReturn(DispatchResult.failed(Channel.SMS, "p1", "error"));

        var outcome = engine.dispatch(sampleRequest(), entity, "t1");

        var smsResult = outcome.results().stream()
            .filter(r -> r.channel() == Channel.SMS).findFirst().orElseThrow();
        assertThat(smsResult.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(outcome.attempts().stream()
            .filter(a -> a.channel() == Channel.SMS)).hasSize(1);
    }

    @Test
    void noProviderMapping_returnsFailedWithExplanation() {
        var smsConfig = new ChannelConfig();
        smsConfig.setEnabled(true);
        smsConfig.setBody(Map.of("default", "Hello"));

        var channels = new ChannelsConfig();
        channels.setSms(smsConfig);

        var entity = new NotificationConfigEntity();
        entity.setChannels(channels);

        when(templateRenderer.render(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new ChannelMessage(Channel.SMS, "Hello", null, null, Map.of()));
        when(mappingResolver.resolve(eq(Channel.SMS), any(), any()))
            .thenReturn(Collections.emptyList());

        var outcome = engine.dispatch(sampleRequest(), entity, "t1");

        var smsResult = outcome.results().stream()
            .filter(r -> r.channel() == Channel.SMS).findFirst().orElseThrow();
        assertThat(smsResult.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(smsResult.reason()).containsIgnoringCase("no provider mapping");
    }

    @Test
    void templateRenderFails_returnsFailedWithRenderException() {
        var smsConfig = new ChannelConfig();
        smsConfig.setEnabled(true);
        smsConfig.setBody(Map.of("default", "Hello {{otp}}"));

        var channels = new ChannelsConfig();
        channels.setSms(smsConfig);

        var entity = new NotificationConfigEntity();
        entity.setChannels(channels);

        when(templateRenderer.render(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new TemplateRenderException(Channel.SMS, "missing variable otp"));

        var outcome = engine.dispatch(sampleRequest(), entity, "t1");

        var smsResult = outcome.results().stream()
            .filter(r -> r.channel() == Channel.SMS).findFirst().orElseThrow();
        assertThat(smsResult.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(smsResult.reason()).contains("missing variable otp");
    }
}
