package org.digit.notify.app.dispatch;

import org.digit.notify.spi.Channel;
import org.digit.notify.spi.Recipient;
import org.jspecify.annotations.Nullable;

/**
 * Which field on a recipient each channel addresses.
 *
 * <p>One place, because two callers ask: the engine asks whether a channel can be attempted at all,
 * and the log asks which address a send actually used. Answered separately, the two drift, and the
 * failure is a log row naming an address the engine had already decided was absent.
 */
public final class RecipientAddresses {

    private RecipientAddresses() {
    }

    /** Whether the channel can be attempted. SMS and WhatsApp both address by phone number. */
    public static boolean isReachable(Channel channel, Recipient recipient) {
        return switch (channel) {
            case EMAIL -> isPresent(recipient.email());
            case SMS, WHATSAPP -> isPresent(recipient.phone());
            case PUSH -> !recipient.deviceTokens().isEmpty();
        };
    }

    /**
     * The address worth recording against a send, or null when there is none worth recording.
     *
     * <p>Null for PUSH even when it is reachable, which is why this is a separate question from
     * {@link #isReachable}: a device token runs past 150 characters, rotates on its own and names no
     * person, so it is not what someone searching this column is looking for.
     */
    public static @Nullable String loggableAddress(Channel channel, Recipient recipient) {
        return switch (channel) {
            case EMAIL -> isPresent(recipient.email()) ? recipient.email() : null;
            case SMS, WHATSAPP -> isPresent(recipient.phone()) ? recipient.phone() : null;
            case PUSH -> null;
        };
    }

    /** Names the missing field the way a caller sees it in the request, so a reason is actionable. */
    public static String nameFor(Channel channel) {
        return switch (channel) {
            case EMAIL -> "email address";
            case SMS, WHATSAPP -> "phone number";
            case PUSH -> "device token";
        };
    }

    /** Blank counts as absent, matching what every provider's own guard checks. */
    public static boolean isPresent(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
