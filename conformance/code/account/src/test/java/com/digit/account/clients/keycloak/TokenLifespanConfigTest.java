package com.digit.account.clients.keycloak;

import com.digit.account.config.AccountProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Token and session lifespans come from config and land on the realm payload as JSON numbers.
 *
 * <p>The template keeps literal numbers so realm_config.json stays valid JSON on disk; the values
 * are overwritten after parsing. These tests pin both halves of that arrangement: the override
 * works, and the template's own numbers still match the property defaults, so an unconfigured
 * deployment provisions the realm it always did.
 */
class TokenLifespanConfigTest {

    private static final String TEMPLATE = "realm_config.json";

    private static KeycloakClient clientWith(AccountProperties.Tokens tokens) {
        AccountProperties.Keycloak cfg = new AccountProperties.Keycloak();
        cfg.setTokens(tokens);
        return new KeycloakClient("http://kc.invalid", "admin", "admin", cfg,
                "https://example.org/{realm}/*", new ObjectMapper());
    }

    /** A realm map carrying the template's literal values, as the parser would hand it over. */
    private static Map<String, Object> templateShaped() {
        Map<String, Object> m = new HashMap<>();
        m.put("accessTokenLifespan", 14400);
        m.put("ssoSessionIdleTimeout", 28800);
        m.put("ssoSessionMaxLifespan", 36000);
        m.put("offlineSessionIdleTimeout", 2592000);
        m.put("offlineSessionMaxLifespan", 5184000);
        return m;
    }

    // ------------------------------------------------------------------ override behaviour

    @Test
    void configuredValuesReplaceTheTemplateValues() {
        AccountProperties.Tokens t = new AccountProperties.Tokens();
        t.setAccessTokenLifespan(300);
        t.setSsoSessionIdleTimeout(600);
        t.setSsoSessionMaxLifespan(1200);
        t.setOfflineSessionIdleTimeout(99);
        t.setOfflineSessionMaxLifespan(88);

        Map<String, Object> realm = templateShaped();
        clientWith(t).applyTokenLifespans(realm);

        assertEquals(300, realm.get("accessTokenLifespan"));
        assertEquals(600, realm.get("ssoSessionIdleTimeout"));
        assertEquals(1200, realm.get("ssoSessionMaxLifespan"));
        assertEquals(99, realm.get("offlineSessionIdleTimeout"));
        assertEquals(88, realm.get("offlineSessionMaxLifespan"));
    }

    @Test
    void theValuesStayJsonNumbersNotStrings() {
        // Keycloak rejects a string where it expects a number, which is the whole reason these are
        // applied after parsing rather than substituted into the template text.
        Map<String, Object> realm = templateShaped();
        clientWith(new AccountProperties.Tokens()).applyTokenLifespans(realm);
        for (String key : templateShaped().keySet()) {
            assertEquals(Integer.class, realm.get(key).getClass(), key);
        }
    }

    @Test
    void defaultsLeaveTheRealmUnchanged() {
        // An unconfigured deployment must provision the same realm as before this became tunable.
        Map<String, Object> before = templateShaped();
        Map<String, Object> after = templateShaped();
        clientWith(new AccountProperties.Tokens()).applyTokenLifespans(after);
        assertEquals(before, after);
    }

    @Test
    void appliesEvenWhenTheTemplateOmitsTheKeys() {
        // put(), not replace(): a template that dropped a field must still come out configured.
        Map<String, Object> realm = new HashMap<>();
        clientWith(new AccountProperties.Tokens()).applyTokenLifespans(realm);
        assertEquals(14400, realm.get("accessTokenLifespan"));
        assertEquals(5, realm.size());
    }

    // ------------------------------------------------------------------ template / default drift

    @Test
    @SuppressWarnings("unchecked")
    void theTemplatesOwnNumbersStillMatchThePropertyDefaults() throws Exception {
        // Guards the "defaults reproduce the old realm" claim against someone editing either side.
        Map<String, Object> template;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE)) {
            assertNotNull(in, TEMPLATE + " not on the test classpath");
            template = new ObjectMapper().readValue(in, Map.class);
        }
        AccountProperties.Tokens d = new AccountProperties.Tokens();
        assertEquals(template.get("accessTokenLifespan"), d.getAccessTokenLifespan());
        assertEquals(template.get("ssoSessionIdleTimeout"), d.getSsoSessionIdleTimeout());
        assertEquals(template.get("ssoSessionMaxLifespan"), d.getSsoSessionMaxLifespan());
        assertEquals(template.get("offlineSessionIdleTimeout"), d.getOfflineSessionIdleTimeout());
        assertEquals(template.get("offlineSessionMaxLifespan"), d.getOfflineSessionMaxLifespan());
    }

    @Test
    void theTemplateIsStillValidJson() {
        // The reason the override lives in Java at all: a {{.Placeholder}} in a numeric slot would
        // break this, and it broke editors before.
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE)) {
            assertNotNull(in, TEMPLATE + " not on the test classpath");
            assertNotNull(new ObjectMapper().readValue(in, Map.class));
        } catch (Exception e) {
            throw new AssertionError("realm_config.json is not valid JSON: " + e.getMessage(), e);
        }
    }
}