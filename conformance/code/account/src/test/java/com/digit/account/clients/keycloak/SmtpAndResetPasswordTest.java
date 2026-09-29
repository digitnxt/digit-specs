package com.digit.account.clients.keycloak;

import com.digit.account.config.AccountProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two realm settings every new tenant needs: forgot-password enabled, and an SMTP server for the
 * realm to send that mail from. They are a pair — the first is useless without the second.
 */
class SmtpAndResetPasswordTest {

    private static KeycloakClient clientWith(AccountProperties.Smtp smtp) {
        AccountProperties.Keycloak cfg = new AccountProperties.Keycloak();
        cfg.setSmtp(smtp);
        return new KeycloakClient("http://kc.invalid", "admin", "admin", cfg,
                "https://example.org/{realm}/*", new ObjectMapper());
    }

    private static AccountProperties.Smtp gmail() {
        AccountProperties.Smtp s = new AccountProperties.Smtp();
        s.setHost("smtp.gmail.com");
        s.setPort("587");
        s.setFrom("digit.sandbox@egovernments.org");
        s.setFromDisplayName("eGov Foundation");
        s.setUser("digit.sandbox@egovernments.org");
        s.setPassword("app-password");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> smtpOf(Map<String, Object> realm) {
        return (Map<String, Object>) realm.get("smtpServer");
    }

    // ------------------------------------------------------------------ forgot password

    @Test
    @SuppressWarnings("unchecked")
    void theTemplateEnablesForgotPassword() throws Exception {
        Map<String, Object> template;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("realm_config.json")) {
            assertNotNull(in, "realm_config.json not on the test classpath");
            template = new ObjectMapper().readValue(in, Map.class);
        }
        assertEquals(Boolean.TRUE, template.get("resetPasswordAllowed"));
    }

    // ------------------------------------------------------------------ smtp population

    @Test
    void aConfiguredHostFillsTheSmtpBlock() {
        Map<String, Object> realm = new HashMap<>();
        realm.put("smtpServer", new HashMap<>());
        clientWith(gmail()).applySmtpServer(realm);

        Map<String, Object> smtp = smtpOf(realm);
        assertEquals("smtp.gmail.com", smtp.get("host"));
        assertEquals("587", smtp.get("port"));
        assertEquals("digit.sandbox@egovernments.org", smtp.get("from"));
        assertEquals("eGov Foundation", smtp.get("fromDisplayName"));
        assertEquals("digit.sandbox@egovernments.org", smtp.get("user"));
        assertEquals("app-password", smtp.get("password"));
    }

    @Test
    void everyValueIsAStringIncludingPortAndToggles() {
        // Keycloak stores smtpServer as string→string and rejects a JSON number or boolean.
        Map<String, Object> realm = new HashMap<>();
        clientWith(gmail()).applySmtpServer(realm);
        smtpOf(realm).forEach((k, v) ->
                assertEquals(String.class, v.getClass(), k + " must be a string, got " + v));
    }

    @Test
    void portFiveEightSevenIsStarttlsNotSsl() {
        // The classic misconfiguration: ssl=true on 587 opens TLS against a plaintext port and every
        // send times out. The defaults must not do that.
        Map<String, Object> realm = new HashMap<>();
        clientWith(gmail()).applySmtpServer(realm);
        assertEquals("true", smtpOf(realm).get("starttls"));
        assertEquals("false", smtpOf(realm).get("ssl"));
    }

    @Test
    void blankReplyToAndEnvelopeFromAreSentAsEmptyNotOmitted() {
        // Keycloak reads an absent key differently from an empty one; empty replyTo means "use from".
        Map<String, Object> realm = new HashMap<>();
        clientWith(gmail()).applySmtpServer(realm);
        Map<String, Object> smtp = smtpOf(realm);
        assertEquals("", smtp.get("replyTo"));
        assertEquals("", smtp.get("replyToDisplayName"));
        assertEquals("", smtp.get("envelopeFrom"));
    }

    // ------------------------------------------------------------------ absent / partial config

    @Test
    void noHostLeavesTheBlockUntouched() {
        // A partial smtpServer is worse than an empty one: Keycloak accepts it and fails at send time,
        // in front of a user mid-reset, rather than at deploy time.
        Map<String, Object> realm = new HashMap<>();
        Map<String, Object> empty = new HashMap<>();
        realm.put("smtpServer", empty);
        clientWith(new AccountProperties.Smtp()).applySmtpServer(realm);
        assertTrue(((Map<?, ?>) realm.get("smtpServer")).isEmpty());
        assertEquals(empty, realm.get("smtpServer"));
    }

    @Test
    void aBlankHostCountsAsAbsent() {
        AccountProperties.Smtp s = gmail();
        s.setHost("   ");
        Map<String, Object> realm = new HashMap<>();
        clientWith(s).applySmtpServer(realm);
        assertNull(realm.get("smtpServer"));
    }

    @Test
    void authOffOmitsTheCredentials() {
        // Keycloak keeps whatever sits in these fields even with auth disabled, so writing them would
        // store a password the realm never uses.
        AccountProperties.Smtp s = gmail();
        s.setAuth(false);
        Map<String, Object> realm = new HashMap<>();
        clientWith(s).applySmtpServer(realm);
        Map<String, Object> smtp = smtpOf(realm);
        assertEquals("false", smtp.get("auth"));
        assertFalse(smtp.containsKey("user"), "user should be absent when auth is off");
        assertFalse(smtp.containsKey("password"), "password should be absent when auth is off");
    }

    @Test
    void defaultsCarryNoCredentialsAndNoHost() {
        // An unconfigured deployment must not ship a half-built SMTP block or a placeholder password.
        AccountProperties.Smtp d = new AccountProperties.Smtp();
        assertEquals("", d.getHost());
        assertEquals("", d.getUser());
        assertEquals("", d.getPassword());
        assertEquals("587", d.getPort());
        assertTrue(d.isStarttls());
        assertFalse(d.isSsl());
        assertTrue(d.isAuth());
    }
}