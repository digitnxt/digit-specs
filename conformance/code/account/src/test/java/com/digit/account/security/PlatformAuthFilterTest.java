package com.digit.account.security;

import com.digit.account.config.AccountProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which requests the lock applies to. The interesting cases are the near-misses: the canonical
 * dialect, the registration paths that share a prefix with the locked ones, and the reads that are
 * meant to stay open.
 */
class PlatformAuthFilterTest {

    private AccountProperties props;
    private PlatformTokenVerifier verifier;
    private PlatformAuthFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        props = new AccountProperties();
        verifier = mock(PlatformTokenVerifier.class);
        when(verifier.expectedIssuer()).thenReturn("https://kc.example.org/keycloak/realms/master");
        when(verifier.verify(any())).thenReturn("subject-1");
        filter = new PlatformAuthFilter(props, verifier);
        chain = mock(FilterChain.class);
    }

    private MockHttpServletResponse call(String method, String servletPath) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, servletPath);
        req.setServletPath(servletPath);
        req.addHeader("Authorization", "Bearer whatever");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
    }

    // ------------------------------------------------------------------ locked

    @Test
    void locksTheThreeTenantLifecycleEndpoints() throws Exception {
        call("POST", "/v3/tenants");
        call("PUT", "/v3/tenants/abc");
        call("DELETE", "/v3/tenants/abc");
        verify(verifier, times(3)).verify(any());
    }

    @Test
    void locksTheCanonicalDialectToo() throws Exception {
        // Without prefix-stripping this would be an unauthenticated route to the same service method.
        call("POST", "/v3/canonical/tenants");
        call("PUT", "/v3/canonical/tenants/abc");
        call("DELETE", "/v3/canonical/tenants/abc");
        verify(verifier, times(3)).verify(any());
    }

    @Test
    void followsAReconfiguredCanonicalPrefix() throws Exception {
        props.getServer().setCanonicalApiPrefix("envelope");
        filter = new PlatformAuthFilter(props, verifier);
        call("POST", "/v3/envelope/tenants");
        verify(verifier).verify(any());
    }

    // ------------------------------------------------------------------ deliberately open

    @Test
    void leavesReadsOpen() throws Exception {
        call("GET", "/v3/tenants");
        call("GET", "/v3/canonical/tenants");
        call("GET", "/v3/config");
        verify(verifier, never()).verify(any());
        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    void leavesRegistrationOpenEvenThoughItSharesThePrefix() throws Exception {
        // POST /v3/tenants is locked; POST /v3/tenants/registrations must not be caught by it.
        call("POST", "/v3/tenants/registrations");
        call("POST", "/v3/tenants/registrations/verify");
        call("POST", "/v3/tenants/registrations/resend");
        verify(verifier, never()).verify(any());
    }

    @Test
    void leavesConfigWritesOpenForNow() throws Exception {
        call("POST", "/v3/config");
        call("PUT", "/v3/config/abc");
        verify(verifier, never()).verify(any());
    }

    // ------------------------------------------------------------------ flags

    @Test
    void enforcesNothingWhenAuthIsDisabled() throws Exception {
        props.getAuth().setEnabled(false);
        filter = new PlatformAuthFilter(props, verifier);
        call("DELETE", "/v3/tenants/abc");
        verify(verifier, never()).verify(any());
    }

    @Test
    void blocksRegistrationWhenSignupIsDisabled() throws Exception {
        props.getSignup().setEnabled(false);
        filter = new PlatformAuthFilter(props, verifier);
        MockHttpServletResponse resp = call("POST", "/v3/tenants/registrations/verify");
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("SIGNUP_DISABLED"), resp.getContentAsString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void disablingSignupDoesNotAffectOtherEndpoints() throws Exception {
        props.getSignup().setEnabled(false);
        filter = new PlatformAuthFilter(props, verifier);
        call("GET", "/v3/tenants");
        verify(chain).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ rejection shape

    @Test
    void returns401WithAChallengeWhenTheTokenIsUnusable() throws Exception {
        when(verifier.verify(any())).thenThrow(
                new PlatformTokenVerifier.Unauthenticated("missing Authorization header"));
        MockHttpServletResponse resp = call("POST", "/v3/tenants");
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getHeader("WWW-Authenticate").contains("realms/master"));
        assertTrue(resp.getContentAsString().contains("UNAUTHENTICATED"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void returns403WhenTheTokenIsValidButLacksTheRole() throws Exception {
        when(verifier.verify(any())).thenThrow(
                new PlatformTokenVerifier.Forbidden("token lacks the SUPERADMIN realm role"));
        MockHttpServletResponse resp = call("DELETE", "/v3/tenants/abc");
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("SUPERADMIN"), resp.getContentAsString());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ extensibility

    @Test
    void anEndpointCanBeMovedBehindTheLockByConfigAlone() throws Exception {
        props.getAuth().setProtectedEndpoints(List.of("POST:/v3/config", "PUT:/v3/config/*"));
        filter = new PlatformAuthFilter(props, verifier);
        call("POST", "/v3/config");
        call("PUT", "/v3/canonical/config/abc");
        verify(verifier, times(2)).verify(any());
    }

    @Test
    void aMalformedRuleFailsFastRatherThanSilentlyNotMatching() {
        props.getAuth().setProtectedEndpoints(List.of("POST /v3/tenants"));
        try {
            new PlatformAuthFilter(props, verifier);
            throw new AssertionError("expected a startup failure for a rule with no colon");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("METHOD:/path"), expected.getMessage());
        }
    }
}
