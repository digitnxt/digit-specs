package com.digit.account.service;

import com.digit.account.clients.keycloak.KeycloakClient;
import com.digit.account.clients.notification.NotificationClient;
import com.digit.account.clients.otp.OtpClient;
import com.digit.account.config.AccountProperties;
import com.digit.account.model.TenantCreateRequest;
import com.digit.account.pubsub.EventPublisher;
import com.digit.account.repository.TenantConfigRepository;
import com.digit.account.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the realm import receives on create: the tenant's own phone as the superuser mobile, and a
 * temporary-password flag set only for a password the service generated. Plus the temp-password
 * email, which is best-effort by design.
 */
class TenantRealmPhoneTest {

    private KeycloakClient keycloak;
    private NotificationClient notification;
    private OtpClient otp;
    private TenantRepository repo;
    private AccountProperties props;
    private TenantService service;

    @BeforeEach
    void setUp() {
        keycloak = mock(KeycloakClient.class);
        notification = mock(NotificationClient.class);
        props = new AccountProperties();
        props.getNotification().setFirstLoginUrls(List.of("admin=https://kc.example.org/keycloak/admin/{realm}/console"));
        otp = mock(OtpClient.class);
        repo = mock(TenantRepository.class);
        service = new TenantService(repo, mock(TenantConfigRepository.class),
                keycloak, notification, otp, mock(EventPublisher.class), props, new ObjectMapper());
    }

    private static TenantCreateRequest req(String phone, String password) {
        TenantCreateRequest r = new TenantCreateRequest();
        r.setName("CityA");
        r.setEmail("admin@citya.example.com");
        r.setPhone(phone);
        r.setPassword(password);
        return r;
    }

    private static final String SUPPLIED = "a-sufficiently-long-password";

    /** Captures the 5th argument (mobileNumber) handed to the realm import. */
    private String capturedMobileNumber() {
        ArgumentCaptor<String> mobile = ArgumentCaptor.forClass(String.class);
        verify(keycloak).createRealmWithFullConfig(anyString(), anyString(), anyString(), anyString(),
                mobile.capture(), anyBoolean());
        return mobile.getValue();
    }

    /** Captures the 6th argument (passwordTemporary). */
    private boolean capturedTemporary() {
        ArgumentCaptor<Boolean> temp = ArgumentCaptor.forClass(Boolean.class);
        verify(keycloak).createRealmWithFullConfig(anyString(), anyString(), anyString(), anyString(),
                any(), temp.capture());
        return temp.getValue();
    }

    // ------------------------------------------------------------------ mobile number

    @Test
    void passesTheTenantsPhoneToTheRealmImport() {
        service.create(req("+919876543210", SUPPLIED), "tester", "req-1");
        assertEquals("+919876543210", capturedMobileNumber());
    }

    @Test
    void passesNothingWhenTheTenantGaveNoPhone() {
        // phone is optional on create, so the absent case must not fall back to a fabricated
        // number — "999999999999" was not even valid E.164 for this service.
        service.create(req(null, SUPPLIED), "tester", "req-1");
        assertNull(capturedMobileNumber());
    }

    // ------------------------------------------------------------------ temporary flag

    @Test
    void aGeneratedPasswordIsMarkedTemporary() {
        service.create(req(null, null), "tester", "req-1");
        assertTrue(capturedTemporary());
    }

    @Test
    void aSuppliedPasswordIsLeftPermanent() {
        // The caller chose it, so forcing them to change it at first login would be gratuitous.
        service.create(req(null, SUPPLIED), "tester", "req-1");
        assertFalse(capturedTemporary());
    }

    @Test
    void anEmptySuppliedPasswordCountsAsGenerated() {
        service.create(req(null, ""), "tester", "req-1");
        assertTrue(capturedTemporary());
    }

    // ------------------------------------------------------------------ temp-password email

    @Test
    void emailsTheGeneratedPasswordToTheTenantAdmin() {
        service.create(req(null, null), "tester", "req-1");
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(notification).sendTempPassword(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("admin@citya.example.com"), pw.capture(),
                anyMap(), anyString());
        assertEquals(10, pw.getValue().length(), pw.getValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedLoginUrls() {
        ArgumentCaptor<Map<String, String>> urls = ArgumentCaptor.forClass(Map.class);
        verify(notification).sendTempPassword(anyString(), anyString(), anyString(), anyString(),
                urls.capture(), anyString());
        return urls.getValue();
    }

    /** The single-URL cases below assert on the one element, which reads better than a list of one. */
    private String capturedLoginUrl() {
        Map<String, String> urls = capturedLoginUrls();
        assertEquals(1, urls.size(), String.valueOf(urls));
        return urls.values().iterator().next();
    }

    @Test
    void substitutesTheTenantCodeIntoTheConfiguredLoginUrl() {
        props.getNotification().setFirstLoginUrls(List.of("admin=https://kc.example.org/keycloak/admin/{realm}/console"));
        service.create(req(null, null), "tester", "req-1");
        assertEquals("https://kc.example.org/keycloak/admin/CITYA/console", capturedLoginUrl());
    }

    @Test
    void acceptsTenantCodeAsThePlaceholderToo() {
        props.getNotification().setFirstLoginUrls(List.of("employee=https://app.example.org/{tenantCode}/employee"));
        service.create(req(null, null), "tester", "req-1");
        assertEquals("https://app.example.org/CITYA/employee", capturedLoginUrl());
    }

    @Test
    void theLoginUrlNeedNotBeKeycloak() {
        // The whole point of a full-URL template: the destination can be a different host entirely.
        props.getNotification().setFirstLoginUrls(List.of("employee=https://digit-lts.digit.org/sandbox-ui/{realm}/employee"));
        service.create(req(null, null), "tester", "req-1");
        assertEquals("https://digit-lts.digit.org/sandbox-ui/CITYA/employee", capturedLoginUrl());
    }

    @Test
    void substitutesEveryOccurrence() {
        props.getNotification().setFirstLoginUrls(List.of("x=https://x.example/{realm}/a/{realm}/b"));
        service.create(req(null, null), "tester", "req-1");
        assertEquals("https://x.example/CITYA/a/CITYA/b", capturedLoginUrl());
    }

    // ------------------------------------------------------------------ multiple login URLs

    @Test
    void emailsEveryConfiguredUrlInOrder() {
        props.getNotification().setFirstLoginUrls(List.of(
                "admin=https://kc.example.org/keycloak/admin/{realm}/console",
                "employee=https://app.example.org/{realm}/employee",
                "citizen=https://app.example.org/{realm}/citizen"));
        service.create(req(null, null), "tester", "req-1");
        assertEquals(new java.util.LinkedHashMap<>(Map.of()) {{
            put("admin", "https://kc.example.org/keycloak/admin/CITYA/console");
            put("employee", "https://app.example.org/CITYA/employee");
            put("citizen", "https://app.example.org/CITYA/citizen");
        }}, capturedLoginUrls());
        // Order is part of the contract: the email lists them as configured.
        assertEquals(List.of("admin", "employee", "citizen"),
                List.copyOf(capturedLoginUrls().keySet()));
    }

    @Test
    void everyUrlIsStoredOnTheRow() {
        props.getNotification().setFirstLoginUrls(List.of(
                "admin=https://a.example/{realm}", "employee=https://b.example/{realm}"));
        ArgumentCaptor<com.digit.account.model.TenantEntity> row =
                ArgumentCaptor.forClass(com.digit.account.model.TenantEntity.class);
        service.create(req(null, null), "tester", "req-1");
        verify(repo).create(row.capture());
        assertEquals(Map.of("admin", "https://a.example/CITYA", "employee", "https://b.example/CITYA"),
                row.getValue().getFirstLoginUrls());
    }

    @Test
    void orderIsPreservedNotSorted() {
        // The first entry is the primary link, so the configured order is part of the contract.
        props.getNotification().setFirstLoginUrls(List.of(
                "zed=https://z.example/{realm}", "alpha=https://a.example/{realm}"));
        service.create(req(null, null), "tester", "req-1");
        assertEquals(List.of("zed", "alpha"), List.copyOf(capturedLoginUrls().keySet()));
    }

    @Test
    void blankEntriesAreDroppedRatherThanEmailedAsEmptyLinks() {
        props.getNotification().setFirstLoginUrls(java.util.Arrays.asList(
                "admin=https://a.example/{realm}", "", "   ", null, "employee=https://b.example/{realm}"));
        service.create(req(null, null), "tester", "req-1");
        assertEquals(Map.of("admin", "https://a.example/CITYA", "employee", "https://b.example/CITYA"),
                capturedLoginUrls());
    }

    @Test
    void anEntirelyBlankListYieldsNoUrlsAtAll() {
        // Not an empty array in the response: that would claim links were sent when none were.
        props.getNotification().setFirstLoginUrls(java.util.Arrays.asList("", "  "));
        assertNull(service.create(req(null, null), "tester", "req-1").getFirstLoginUrls());
    }

    @Test
    void anUnsetListYieldsNoUrlsAtAll() {
        props.getNotification().setFirstLoginUrls(List.of());
        assertNull(service.create(req(null, null), "tester", "req-1").getFirstLoginUrls());
    }

    @Test
    void aNullListIsTreatedAsUnsetRatherThanCrashing() {
        props.getNotification().setFirstLoginUrls(null);
        assertNull(service.create(req(null, null), "tester", "req-1").getFirstLoginUrls());
    }

    @Test
    void oneConfiguredUrlStillProducesExactlyOne() {
        // The single-URL deployment must be unaffected by the list becoming plural.
        props.getNotification().setFirstLoginUrls(List.of("admin=https://only.example/{realm}"));
        assertEquals(Map.of("admin", "https://only.example/CITYA"),
                service.create(req(null, null), "tester", "req-1").getFirstLoginUrls());
    }

    // ------------------------------------------------------------------ first-login URL presence

    @Test
    void aGeneratedPasswordGetsAFirstLoginUrl() {
        assertEquals(Map.of("admin", "https://kc.example.org/keycloak/admin/CITYA/console"),
                service.create(req(null, null), "tester", "req-1").getFirstLoginUrls());
    }

    @Test
    void aSuppliedPasswordGetsNoFirstLoginUrl() {
        // Nothing was emailed, so there is no link to redeem and none to report. Left null so
        // @JsonInclude(NON_NULL) drops the field rather than returning a URL that means nothing.
        assertNull(service.create(req(null, SUPPLIED), "tester", "req-1").getFirstLoginUrls());
    }

    @Test
    void aSuppliedPasswordStoresNoFirstLoginUrl() {
        // Absent from the row too, not just the response: the column records what was emailed.
        ArgumentCaptor<com.digit.account.model.TenantEntity> row =
                ArgumentCaptor.forClass(com.digit.account.model.TenantEntity.class);
        service.create(req(null, SUPPLIED), "tester", "req-1");
        verify(repo).create(row.capture());
        assertNull(row.getValue().getFirstLoginUrls());
    }

    @Test
    void aWhitespacePasswordStillGetsAFirstLoginUrl() {
        // It counts as absent, so a password is generated and emailed — and therefore has a link.
        assertEquals(Map.of("admin", "https://kc.example.org/keycloak/admin/CITYA/console"),
                service.create(req(null, "          "), "tester", "req-1").getFirstLoginUrls());
    }

    @Test
    void neverEmailsAPasswordTheCallerSupplied() {
        service.create(req(null, SUPPLIED), "tester", "req-1");
        verify(notification, never()).sendTempPassword(anyString(), anyString(), anyString(),
                anyString(), anyMap(), anyString());
    }

    @Test
    void reportsTheEmailAsSentWhenItSucceeds() {
        when(notification.sendTempPassword(anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyString())).thenReturn(true);
        assertEquals(Boolean.TRUE,
                service.create(req(null, null), "tester", "req-1").getTemporaryPasswordEmailed());
    }

    @Test
    void reportsTheEmailAsNotSentWhenItFails() {
        doThrow(new RuntimeException("notification down")).when(notification)
                .sendTempPassword(anyString(), anyString(), anyString(), anyString(), anyMap(),
                        anyString());
        assertEquals(Boolean.FALSE,
                service.create(req(null, null), "tester", "req-1").getTemporaryPasswordEmailed());
    }

    @Test
    void reportsTheEmailAsNotSentWhenNotificationIsSwitchedOff() {
        // The client returns false rather than throwing; being disabled is not an error, but the
        // admin still never received the password.
        when(notification.sendTempPassword(anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyString())).thenReturn(false);
        assertEquals(Boolean.FALSE,
                service.create(req(null, null), "tester", "req-1").getTemporaryPasswordEmailed());
    }

    @Test
    void omitsTheFieldEntirelyWhenTheCallerSuppliedAPassword() {
        // Nothing was there to deliver, and false would read as a delivery that failed.
        assertNull(service.create(req(null, SUPPLIED), "tester", "req-1").getTemporaryPasswordEmailed());
    }

    @Test
    void aWhitespaceOnlyPasswordCountsAsAbsent() {
        // Long enough to pass the minimum-length check, but not a usable credential.
        assertTrue(service.create(req(null, "          "), "tester", "req-1").isPasswordGenerated());
        assertTrue(capturedTemporary());
    }


    @Test
    void aFailedEmailDoesNotFailTheRequest() {
        // The tenant and realm already exist; losing the email must not undo them.
        doThrow(new RuntimeException("notification down")).when(notification)
                .sendTempPassword(anyString(), anyString(), anyString(), anyString(),
                anyMap(), anyString());
        assertEquals("CITYA", service.create(req(null, null), "tester", "req-1").getCode());
    }
}
