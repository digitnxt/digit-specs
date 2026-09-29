package org.digit.notify.provider.smscountry;

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
import java.util.Map;

public class SmsCountryProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(SmsCountryProvider.class);

    private final SmsCountryProviderConfig config;
    private final HttpClient httpClient;

    public SmsCountryProvider() {
        this.config = new SmsCountryProviderConfig();
        this.httpClient = HttpClient.newHttpClient();
        log.info("SmsCountryProvider initialised with sender ID: {}",
                config.hasSenderId() ? config.getSenderId() : "<account default>");
    }

    SmsCountryProvider(SmsCountryProviderConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public Channel supportedChannel() {
        return Channel.SMS;
    }

    @Override
    public String providerName() {
        return "smscountry";
    }

    @Override
    public DispatchResult send(ChannelMessage message, Recipient recipient, Map<String, Object> metadata) {
        if (recipient.phone() == null || recipient.phone().isBlank()) {
            return DispatchResult.failed(Channel.SMS, "smscountry", "Recipient phone is missing");
        }

        try {
            String phone = recipient.phone().startsWith("+")
                ? recipient.phone().substring(1)
                : recipient.phone();

            // sid is omitted rather than sent empty when no sender id is configured, so the
            // gateway falls back to the header registered against the account.
            String formBody = "User=" + encode(config.getUsername())
                + "&passwd=" + encode(config.getPassword())
                + "&mobilenumber=" + encode(phone)
                + "&message=" + encode(message.renderedBody())
                + (config.hasSenderId() ? "&sid=" + encode(config.getSenderId()) : "")
                + "&mtype=N"
                + "&DR=Y";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("SMSCountry response [{}]: {}", response.statusCode(), response.body());

            return interpret(response.statusCode(), response.body());

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("SMSCountry failed to send to {}: {}", recipient.phone(), e.getMessage());
            return DispatchResult.failed(Channel.SMS, "smscountry", e.getMessage());
        }
    }

    /**
     * Turns a gateway reply into a dispatch result.
     *
     * <p>Separate from {@link #send} and free of any HTTP type so the decision can be tested
     * against the bodies the gateway actually returns — which is what went wrong: this used to
     * answer {@code dispatched} for any 200, and SMSCountry answers 200 for refusals as well. An
     * unauthorised account gets {@code 200 "No ACCESS Permission."}, so every rejected message was
     * reported as sent and the SMS simply never arrived.
     *
     * <p>Its bulk API prefixes an accepted submission with {@code OK}, so that is the only body
     * treated as acceptance. Erring this way is deliberate: an accepted reply that somehow does not
     * start with OK surfaces as a FAILED carrying the exact body, which the log line in
     * {@link #send} makes obvious at once — whereas a silent success cannot be recovered from.
     */
    static DispatchResult interpret(int statusCode, String rawBody) {
        String body = rawBody == null ? "" : rawBody.trim();

        if (statusCode != 200 && statusCode != 201) {
            String reason = "SMSCountry returned HTTP " + statusCode + ": " + body;
            log.warn(reason);
            return DispatchResult.failed(Channel.SMS, "smscountry", reason);
        }
        if (!body.regionMatches(true, 0, "OK", 0, 2)) {
            String reason = "SMSCountry refused the message: " + body;
            log.warn(reason);
            return DispatchResult.failed(Channel.SMS, "smscountry", reason);
        }
        return DispatchResult.dispatched(Channel.SMS, "smscountry");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
