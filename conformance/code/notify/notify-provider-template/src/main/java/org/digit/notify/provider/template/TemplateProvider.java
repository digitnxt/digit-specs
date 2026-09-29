package org.digit.notify.provider.template;

import org.digit.notify.spi.Channel;
import org.digit.notify.spi.ChannelMessage;
import org.digit.notify.spi.DispatchResult;
import org.digit.notify.spi.NotificationChannelProvider;
import org.digit.notify.spi.Recipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class TemplateProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(TemplateProvider.class);

    // ServiceLoader requires a public no-arg constructor
    public TemplateProvider() {
        log.info("TemplateProvider initialised");
        // TODO: initialise TemplateProviderConfig here once you have set env vars
        // new TemplateProviderConfig();
    }

    @Override
    public Channel supportedChannel() {
        return Channel.SMS; // TODO: change to your target channel
    }

    @Override
    public String providerName() {
        return "template"; // TODO: change to your provider name e.g. "twilio"
    }

    @Override
    public DispatchResult send(
        ChannelMessage message,
        Recipient recipient,
        Map<String, Object> metadata
    ) {
        // TODO: implement your provider API call here
        //
        // For SMS/WhatsApp use:  message.renderedBody(), recipient.phone()
        // For Email use:         message.renderedSubject(), message.renderedBody(),
        //                        recipient.email()
        // For Push use:          message.renderedTitle(), message.renderedBody(),
        //                        recipient.deviceTokens()
        //
        // On success: return DispatchResult.dispatched(supportedChannel(), providerName())
        // On failure: return DispatchResult.failed(supportedChannel(), providerName(), reason)
        //
        // Do NOT throw exceptions — always return a DispatchResult.
        // The dispatch engine handles fallback based on DispatchStatus.

        throw new UnsupportedOperationException(
            "TemplateProvider.send() not implemented — this is a template");
    }
}
