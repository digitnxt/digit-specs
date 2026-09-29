package org.digit.notify.provider.gmail;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.digit.notify.spi.Channel;
import org.digit.notify.spi.ChannelMessage;
import org.digit.notify.spi.DispatchResult;
import org.digit.notify.spi.NotificationChannelProvider;
import org.digit.notify.spi.Recipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class GmailProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(GmailProvider.class);

    private final GmailProviderConfig config;
    private final Session session;

    // ServiceLoader requires a public no-arg constructor
    // This is called by ProviderPluginLoader when it loads your jar
    public GmailProvider() {
        this.config = new GmailProviderConfig();
        this.session = buildSession();
        log.info("GmailProvider initialised with from address: {}", config.getFromAddress());
    }

    // Constructor for testing only — skips env vars and SMTP session
    GmailProvider(GmailProviderConfig config, Session session) {
        this.config = config;
        this.session = session;
    }

    // Method 1 — tells ProviderPluginLoader this provider handles EMAIL
    // registry.get(EMAIL).put("gmail", this)  ← this is what happens after
    @Override
    public Channel supportedChannel() {
        return Channel.EMAIL;
    }

    // Method 2 — the unique name used in provider mappings
    // when you configure providers: ["gmail"] in the DB, this name is matched
    @Override
    public String providerName() {
        return "gmail";
    }

    // Method 3 — the actual sending
    // DispatchEngine calls this after rendering the template
    // message.renderedBody()    → the email body, already filled with real values
    // message.renderedSubject() → the email subject, already filled with real values
    // recipient.email()         → the address to send to
    @Override
    public DispatchResult send(
        ChannelMessage message,
        Recipient recipient,
        Map<String, Object> metadata
    ) {
        // Guard: if no email address on the recipient, we can't send
        if (recipient.email() == null || recipient.email().isBlank()) {
            return DispatchResult.failed(Channel.EMAIL, "gmail", "Recipient email is missing");
        }

        try {
            // Build the email
            MimeMessage email = new MimeMessage(session);

            // FROM — the Gmail address, with an optional display name so the recipient sees
            // a sender name rather than the bare address. UTF-8 so non-ASCII names survive.
            email.setFrom(config.hasFromName()
                ? new InternetAddress(config.getFromAddress(), config.getFromName(), "UTF-8")
                : new InternetAddress(config.getFromAddress()));

            // TO — the recipient's email address from Recipient.email()
            email.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient.email()));

            // SUBJECT — rendered by TemplateRenderer before send() was called
            // e.g. "Your OTP for login" (already has real values, not a template)
            String subject = message.renderedSubject() != null ? message.renderedSubject() : "(no subject)";
            email.setSubject(subject);

            // BODY — rendered by TemplateRenderer before send() was called
            // e.g. "Hello Alice, your OTP is 1234" (already has real values)
            email.setText(message.renderedBody(), "UTF-8");

            // Actually send via Gmail SMTP
            Transport.send(email);

            log.info("Email sent to {} via gmail", recipient.email());

            // Tell DispatchEngine it worked — stops fallback chain
            return DispatchResult.dispatched(Channel.EMAIL, "gmail");

        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.warn("Gmail failed to send to {}: {}", recipient.email(), e.getMessage());

            // Tell DispatchEngine it failed — triggers fallback to next provider
            return DispatchResult.failed(Channel.EMAIL, "gmail", e.getMessage());
        }
    }

    // Builds the Gmail SMTP connection settings
    // Called once in the constructor — reused for every email sent
    private Session buildSession() {
        Properties props = new Properties();

        // Use Gmail's SMTP server
        props.put("mail.smtp.host", "smtp.gmail.com");

        // Port 587 is the standard port for SMTP with STARTTLS
        props.put("mail.smtp.port", "587");

        // STARTTLS upgrades the connection to encrypted after connecting
        props.put("mail.smtp.starttls.enable", "true");

        // We will authenticate with Gmail using fromAddress + appPassword
        props.put("mail.smtp.auth", "true");

        // Authenticator provides the credentials when Gmail SMTP asks for them
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                    config.getFromAddress(),  // Gmail address
                    config.getAppPassword()   // App password from env var
                );
            }
        });
    }
}
