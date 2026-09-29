package org.digit.notify.provider.twilio;

import org.digit.notify.spi.Channel;
import org.digit.notify.spi.ChannelMessage;
import org.digit.notify.spi.DispatchResult;
import org.digit.notify.spi.NotificationChannelProvider;
import org.digit.notify.spi.Recipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public class TwilioWhatsAppProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(TwilioWhatsAppProvider.class);
    private static final String API_URL =
        "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private final TwilioWhatsAppProviderConfig config;
    private final HttpClient httpClient;

    public TwilioWhatsAppProvider() {
        this.config = new TwilioWhatsAppProviderConfig();
        this.httpClient = HttpClient.newHttpClient();
        log.info("TwilioWhatsAppProvider initialised with from number: {}", config.getFromNumber());
    }

    TwilioWhatsAppProvider(TwilioWhatsAppProviderConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public Channel supportedChannel() {
        return Channel.WHATSAPP;
    }

    @Override
    public String providerName() {
        return "twilio-whatsapp";
    }

    @Override
    public DispatchResult send(ChannelMessage message, Recipient recipient, Map<String, Object> metadata) {
        if (recipient.phone() == null || recipient.phone().isBlank()) {
            return DispatchResult.failed(Channel.WHATSAPP, "twilio-whatsapp", "Recipient phone is missing");
        }

        try {
            // Twilio requires whatsapp: prefix on both From and To numbers
            String to = "whatsapp:" + recipient.phone();

            String formBody = "From=" + encode(config.getFromNumber())
                + "&To=" + encode(to)
                + "&Body=" + encode(message.renderedBody());

            String credentials = Base64.getEncoder().encodeToString(
                (config.getAccountSid() + ":" + config.getAuthToken()).getBytes()
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(API_URL, config.getAccountSid())))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                log.info("WhatsApp message sent to {} via twilio", recipient.phone());
                return DispatchResult.dispatched(Channel.WHATSAPP, "twilio-whatsapp");
            }

            String reason = "Twilio returned HTTP " + response.statusCode() + ": " + response.body();
            log.warn(reason);
            return DispatchResult.failed(Channel.WHATSAPP, "twilio-whatsapp", reason);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Twilio WhatsApp failed to send to {}: {}", recipient.phone(), e.getMessage());
            return DispatchResult.failed(Channel.WHATSAPP, "twilio-whatsapp", e.getMessage());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
