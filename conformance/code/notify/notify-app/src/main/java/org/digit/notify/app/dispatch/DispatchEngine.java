package org.digit.notify.app.dispatch;

import com.digit.tenant.migration.web.TenantContext;
import org.digit.notify.app.domain.entity.NotificationConfigEntity;
import org.digit.notify.app.domain.entity.config.ChannelConfig;
import org.digit.notify.app.domain.entity.config.EmailChannelConfig;
import org.digit.notify.app.domain.entity.config.PushChannelConfig;
import org.digit.notify.app.model.NotifyRequest;
import org.digit.notify.app.plugin.ProviderPluginLoader;
import org.digit.notify.app.template.TemplateRenderException;
import org.digit.notify.app.template.TemplateRenderer;
import org.digit.notify.spi.Channel;
import org.digit.notify.spi.DispatchResult;
import org.digit.notify.spi.DispatchStatus;
import org.digit.notify.spi.NotificationChannelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;

@Component
public class DispatchEngine {

    private static final Logger log = LoggerFactory.getLogger(DispatchEngine.class);

    private final ProviderMappingResolver mappingResolver;
    private final ProviderPluginLoader pluginLoader;
    private final TemplateRenderer templateRenderer;

    public DispatchEngine(ProviderMappingResolver mappingResolver,
                          ProviderPluginLoader pluginLoader,
                          TemplateRenderer templateRenderer) {
        this.mappingResolver = mappingResolver;
        this.pluginLoader = pluginLoader;
        this.templateRenderer = templateRenderer;
    }

    public DispatchOutcome dispatch(
        NotifyRequest request,
        NotificationConfigEntity config,
        String tenantId
    ) {
        var results = new CopyOnWriteArrayList<DispatchResult>();
        var attempts = new CopyOnWriteArrayList<AttemptRecord>();

        // Read on the request thread, while the binding still exists. TenantContext is a plain
        // ThreadLocal and every task below runs on a fresh virtual thread, which inherits none —
        // so without carrying the schema across, each fork borrows a connection with search_path
        // at the shared schema and cannot see the tenant's own provider_mapping rows. The symptom
        // is not an error but a wrong answer: "No provider mapping found for channel EMAIL" for a
        // tenant whose mapping GET /v3/provider-mappings lists quite happily, because that request
        // never leaves its own thread.
        String schema = TenantContext.getSchema();

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            for (Channel channel : Channel.values()) {
                scope.fork(() -> {
                    // Correct in both modes without a branch: with separation off the captured
                    // schema is already the shared one.
                    try (var bound = TenantContext.open(tenantId, schema)) {
                        var result = dispatchChannel(channel, request, config, tenantId, attempts);
                        results.add(result);
                    }
                    return null;
                });
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Dispatch interrupted", e);
        }

        return new DispatchOutcome(List.copyOf(results), List.copyOf(attempts));
    }

    private DispatchResult dispatchChannel(
        Channel channel,
        NotifyRequest request,
        NotificationConfigEntity config,
        String tenantId,
        List<AttemptRecord> attempts
    ) {
        var channelConfig = getChannelConfig(config, channel);
        if (channelConfig == null || !channelConfig.isEnabled()) {
            return DispatchResult.skipped(channel, "Channel disabled or not configured");
        }

        // The config says which channels are allowed; the recipient says which are reachable. A
        // caller that supplied no phone number is choosing not to send an SMS, and that reads as
        // SKIPPED here rather than as a failure.
        //
        // Every provider already refuses a missing address, so without this the same outcome
        // arrives as FAILED — and FAILED is indistinguishable from a vendor outage, both in
        // notification_attempt and to anything alerting on it. It also arrives expensively: the
        // template is rendered and the whole fallback chain is walked, one FAILED row per provider,
        // each reporting the same missing address, while the response says only "All providers
        // exhausted" and never names the real cause.
        //
        // Checked after the enabled test, because "not configured" is the more specific answer when
        // both are true.
        if (!RecipientAddresses.isReachable(channel, request.recipient())) {
            return DispatchResult.skipped(channel,
                "No " + RecipientAddresses.nameFor(channel) + " on recipient");
        }

        var bodyTemplates = channelConfig.getBody() != null ? channelConfig.getBody() : Collections.<String, String>emptyMap();
        Map<String, String> subjectTemplates = channelConfig instanceof EmailChannelConfig e ? e.getSubject() : null;
        Map<String, String> titleTemplates = channelConfig instanceof PushChannelConfig p ? p.getTitle() : null;
        var payloadBindings = channelConfig.getPayloadBindings();

        org.digit.notify.spi.ChannelMessage message;
        try {
            message = templateRenderer.render(
                channel, bodyTemplates, subjectTemplates, titleTemplates,
                payloadBindings, request.payload(), request.locale());
        } catch (TemplateRenderException e) {
            log.warn("Template render failed for channel {}: {}", channel, e.getMessage());
            attempts.add(new AttemptRecord(channel, "none", 1, DispatchStatus.FAILED, cap(e.getMessage()), Instant.now()));
            return DispatchResult.failed(channel, "none", e.getMessage());
        }

        var providerNames = mappingResolver.resolve(channel, request.recipient().countryCode(), tenantId);
        if (providerNames.isEmpty()) {
            String reason = "No provider mapping found for channel " + channel;
            attempts.add(new AttemptRecord(channel, "none", 1, DispatchStatus.FAILED, cap(reason), Instant.now()));
            return DispatchResult.failed(channel, "none", reason);
        }

        var providers = pluginLoader.getProvidersOrdered(channel, providerNames);
        if (providers.isEmpty()) {
            String reason = "No active providers registered for channel " + channel;
            attempts.add(new AttemptRecord(channel, "none", 1, DispatchStatus.FAILED, cap(reason), Instant.now()));
            return DispatchResult.failed(channel, "none", reason);
        }

        int attemptNo = 1;
        for (NotificationChannelProvider provider : providers) {
            DispatchResult result;
            try {
                result = provider.send(message, request.recipient(), request.metadata());
            } catch (Exception e) {
                log.warn("Provider {} threw exception for channel {}: {}", provider.providerName(), channel, e.getMessage());
                result = DispatchResult.failed(channel, provider.providerName(), e.getMessage());
            }

            attempts.add(new AttemptRecord(
                channel, provider.providerName(), attemptNo,
                result.status(), cap(result.reason()), Instant.now()));

            if (result.status() == DispatchStatus.DISPATCHED) {
                return result;
            }

            log.warn("Provider {} failed for channel {}, trying fallback", provider.providerName(), channel);
            attemptNo++;
        }

        return DispatchResult.failed(channel, providers.getLast().providerName(),
            "All providers exhausted for channel " + channel);
    }

    private static String cap(String reason) {
        if (reason == null) return null;
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }

    private ChannelConfig getChannelConfig(NotificationConfigEntity config, Channel channel) {
        if (config.getChannels() == null) return null;
        return switch (channel) {
            case SMS -> config.getChannels().getSms();
            case EMAIL -> config.getChannels().getEmail();
            case WHATSAPP -> config.getChannels().getWhatsapp();
            case PUSH -> config.getChannels().getPush();
        };
    }
}
