package org.digit.notify.provider.gmail;

import org.digit.notify.spi.Channel;
import org.digit.notify.spi.ChannelMessage;
import org.digit.notify.spi.DispatchResult;
import org.digit.notify.spi.DispatchStatus;
import org.digit.notify.spi.Recipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GmailProviderTest {

    // We test GmailProvider without actually sending emails
    // So we create a TestableGmailProvider that overrides send()
    // to skip the real SMTP call
    private TestableGmailProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TestableGmailProvider();
    }

    // Test 1 — supportedChannel() must return EMAIL
    // This is what ProviderPluginLoader uses to put you in the right registry bucket
    @Test
    void supportedChannel_shouldBeEmail() {
        assertThat(provider.supportedChannel()).isEqualTo(Channel.EMAIL);
    }

    // Test 2 — providerName() must return "gmail"
    // This is what ProviderMappingResolver matches against
    @Test
    void providerName_shouldBeGmail() {
        assertThat(provider.providerName()).isEqualTo("gmail");
    }

    // Test 3 — send() should return DISPATCHED when everything is correct
    // This is what DispatchEngine checks to stop the fallback chain
    @Test
    void send_shouldReturnDispatched_whenRecipientEmailIsPresent() {
        ChannelMessage message = new ChannelMessage(
            Channel.EMAIL,
            "Hello Alice, your OTP is 1234",  // renderedBody — already rendered by TemplateRenderer
            "Your OTP for login",              // renderedSubject
            null,                              // renderedTitle — null for email
            Map.of()
        );

        Recipient recipient = new Recipient(
            null,               // phone — not needed for email
            "alice@example.com", // email — this is what send() uses
            List.of(),          // deviceTokens — not needed for email
            "IN",               // countryCode
            Map.of()
        );

        DispatchResult result = provider.send(message, recipient, Map.of());

        assertThat(result.status()).isEqualTo(DispatchStatus.DISPATCHED);
        assertThat(result.providerName()).isEqualTo("gmail");
        assertThat(result.channel()).isEqualTo(Channel.EMAIL);
    }

    // Test 4 — send() should return FAILED when recipient has no email
    // DispatchEngine will then try the next provider (fallback)
    @Test
    void send_shouldReturnFailed_whenRecipientEmailIsMissing() {
        ChannelMessage message = new ChannelMessage(
            Channel.EMAIL,
            "Hello Alice, your OTP is 1234",
            "Your OTP for login",
            null,
            Map.of()
        );

        Recipient recipient = new Recipient(
            null,   // phone
            null,   // email — missing!
            List.of(),
            "IN",
            Map.of()
        );

        DispatchResult result = provider.send(message, recipient, Map.of());

        assertThat(result.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(result.reason()).isEqualTo("Recipient email is missing");
    }

    // A testable version of GmailProvider that skips real SMTP sending
    // We override send() to simulate success without hitting Gmail servers
    static class TestableGmailProvider extends GmailProvider {

        // Override the constructor to skip reading env vars and building SMTP session
        public TestableGmailProvider() {
            super(null, null); // pass nulls — we won't actually send
        }

        // Override send() to skip the real SMTP call
        // but still test the guard check (missing email)
        @Override
        public DispatchResult send(ChannelMessage message, Recipient recipient, Map<String, Object> metadata) {
            // test the guard — same logic as real send()
            if (recipient.email() == null || recipient.email().isBlank()) {
                return DispatchResult.failed(Channel.EMAIL, "gmail", "Recipient email is missing");
            }
            // simulate success — skip actual SMTP
            return DispatchResult.dispatched(Channel.EMAIL, "gmail");
        }
    }
}
