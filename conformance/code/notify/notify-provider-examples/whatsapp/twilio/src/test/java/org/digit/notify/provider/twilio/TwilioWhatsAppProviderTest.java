package org.digit.notify.provider.twilio;

import org.digit.notify.spi.Channel;
import org.digit.notify.spi.ChannelMessage;
import org.digit.notify.spi.DispatchStatus;
import org.digit.notify.spi.Recipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwilioWhatsAppProviderTest {

    @Test
    void returns_failed_when_phone_is_missing() {
        var provider = new TwilioWhatsAppProvider();
        var recipient = new Recipient(null, null, List.of(), null, Map.of());
        var message = new ChannelMessage(Channel.WHATSAPP, "Hello", null, null, Map.of());

        var result = provider.send(message, recipient, Map.of());

        assertThat(result.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(result.reason()).contains("phone is missing");
    }

    /** Hits the real Twilio API, so it only runs where an account is configured. */
    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_PHONE", matches = ".+")
    void sends_real_whatsapp_message_with_live_credentials() {
        var provider = new TwilioWhatsAppProvider();
        var recipient = new Recipient(System.getenv("TEST_PHONE"), null, List.of(), "IN", Map.of());
        var message = new ChannelMessage(Channel.WHATSAPP, "notify-service test WhatsApp message", null, null, Map.of());

        var result = provider.send(message, recipient, Map.of());

        assertThat(result.status()).isEqualTo(DispatchStatus.DISPATCHED);
    }
}
