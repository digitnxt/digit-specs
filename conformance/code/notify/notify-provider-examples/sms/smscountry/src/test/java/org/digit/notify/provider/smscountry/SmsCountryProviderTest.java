package org.digit.notify.provider.smscountry;

import org.digit.notify.spi.Channel;
import org.digit.notify.spi.ChannelMessage;
import org.digit.notify.spi.DispatchStatus;
import org.digit.notify.spi.Recipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmsCountryProviderTest {

    @Test
    void returns_failed_when_phone_is_missing() {
        var provider = new SmsCountryProvider();
        var recipient = new Recipient(null, null, List.of(), null, Map.of());
        var message = new ChannelMessage(Channel.SMS, "Hello", null, null, Map.of());

        var result = provider.send(message, recipient, Map.of());

        assertThat(result.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(result.reason()).contains("phone is missing");
    }

    /**
     * The defect the interpret() split exists for. SMSCountry answers 200 for refusals as well as
     * acceptances, so the old status-only check reported every rejected message as DISPATCHED — the
     * API said the SMS had been sent and it never arrived. This body is verbatim what an
     * unauthorised account returns.
     */
    @Test
    void a_200_carrying_a_refusal_is_failed_not_dispatched() {
        var result = SmsCountryProvider.interpret(200, "No ACCESS Permission.");

        assertThat(result.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(result.reason()).contains("No ACCESS Permission.");
    }

    @Test
    void an_accepted_submission_is_dispatched() {
        assertThat(SmsCountryProvider.interpret(200, "OK:1234567").status())
                .isEqualTo(DispatchStatus.DISPATCHED);
        // Case-insensitive prefix: the gateway is not consistent about it.
        assertThat(SmsCountryProvider.interpret(200, "ok").status())
                .isEqualTo(DispatchStatus.DISPATCHED);
        // Surrounding whitespace is stripped before the check.
        assertThat(SmsCountryProvider.interpret(201, "  OK  ").status())
                .isEqualTo(DispatchStatus.DISPATCHED);
    }

    /** An empty reply is not evidence that anything was accepted. */
    @Test
    void an_empty_body_is_not_treated_as_acceptance() {
        assertThat(SmsCountryProvider.interpret(200, "").status())
                .isEqualTo(DispatchStatus.FAILED);
        assertThat(SmsCountryProvider.interpret(200, null).status())
                .isEqualTo(DispatchStatus.FAILED);
    }

    @Test
    void a_non_2xx_status_is_failed_and_names_the_status() {
        var result = SmsCountryProvider.interpret(503, "Service Unavailable");

        assertThat(result.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(result.reason()).contains("503").contains("Service Unavailable");
    }

    /**
     * Hits the real gateway, so it only runs where an account is configured. Unguarded it asserted
     * DISPATCHED and failed for anyone without credentials — which nobody noticed, because this
     * module is not part of any build.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_PHONE", matches = ".+")
    void sends_real_sms_with_live_credentials() {
        var provider = new SmsCountryProvider();
        var recipient = new Recipient(System.getenv("TEST_PHONE"), null, List.of(), "IN", Map.of());
        var message = new ChannelMessage(Channel.SMS, "notify-service test SMS", null, null, Map.of());

        var result = provider.send(message, recipient,
                Map.of("dltTemplateId", System.getenv("TEST_DLT_TEMPLATE_ID")));

        assertThat(result.status()).isEqualTo(DispatchStatus.DISPATCHED);
    }
}
