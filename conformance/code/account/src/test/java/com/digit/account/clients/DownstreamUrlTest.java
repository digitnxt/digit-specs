package com.digit.account.clients;

import com.digit.account.clients.otp.OtpClient;
import com.digit.account.config.AccountProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OTP client concatenates base URL + path with no normalisation, so the
 * convention holds it together: the base URL is host+port only and ends with a slash, while each
 * configured endpoint path carries the downstream context path and does not begin with one. Keycloak
 * is excluded — it builds its own URLs from a slash-free base. Drives the clients against a local
 * server and asserts the request-target that actually arrives.
 */
class DownstreamUrlTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> received = new AtomicReference<>();
    private String body = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        // Trailing slash, no context path: the convention every internal base-url default follows.
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.set(exchange.getRequestURI().toString());
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private AccountProperties.Otp otpConfig() {
        AccountProperties.Otp cfg = new AccountProperties.Otp();
        cfg.setBaseUrl(baseUrl);
        cfg.setTimeoutSeconds(5);
        return cfg;
    }

    @Test
    void otpGenerateUrl_carriesContextPathWithSingleSlash() {
        body = "{\"referenceId\":\"ref-1\",\"expiresIn\":60,\"cooldownSeconds\":30}";
        OtpClient client = new OtpClient(otpConfig(), new ObjectMapper());

        OtpClient.GenerateResponse resp = client.generate("9999999999", "mobile", "signup", Map.of());

        assertEquals("ref-1", resp.referenceId);
        assertEquals("/otp/v3/generate", received.get());
    }

    @Test
    void otpVerifyUrl_carriesContextPathWithSingleSlash() {
        body = "{\"verified\":true,\"purpose\":\"signup\"}";
        new OtpClient(otpConfig(), new ObjectMapper()).verify("ref-1", "123456", "signup");

        assertEquals("/otp/v3/verify", received.get());
    }

    @Test
    void otpResendUrl_carriesContextPathWithSingleSlash() {
        body = "{\"referenceId\":\"ref-1\",\"expiresIn\":60,\"cooldownSeconds\":30,\"purpose\":\"signup\"}";
        new OtpClient(otpConfig(), new ObjectMapper()).resend("ref-1", Map.of());

        assertEquals("/otp/v3/resend", received.get());
    }

    /**
     * Endpoint paths are config-driven, not baked into the client: re-pointing them (as a deployment
     * would, to re-route or version-bump an endpoint) must change the URL actually requested.
     */
    @Test
    void endpointPaths_areTakenFromConfig() {
        AccountProperties.Otp otp = otpConfig();
        otp.setGeneratePath("otp/v4/generate");
        body = "{\"referenceId\":\"ref-1\",\"expiresIn\":60,\"cooldownSeconds\":30}";
        new OtpClient(otp, new ObjectMapper()).generate("9999999999", "mobile", "signup", Map.of());
        assertEquals("/otp/v4/generate", received.get());

        AccountProperties.Otp verify = otpConfig();
        verify.setVerifyPath("otp/v4/verify");
        body = "{\"verified\":true,\"purpose\":\"signup\"}";
        new OtpClient(verify, new ObjectMapper()).verify("ref-1", "123456", "signup");
        assertEquals("/otp/v4/verify", received.get());
    }

    /**
     * The clients only produce correct URLs if the shipped defaults obey the convention, so guard the
     * defaults themselves against drift.
     */
    @Test
    void shippedDefaults_baseUrlEndsWithSlash_andPathsCarryTheContextPath() {
        AccountProperties defaults = new AccountProperties();

        for (String base : List.of(defaults.getOtp().getBaseUrl())) {
            assertTrue(base.endsWith("/"), "base url must end with a slash: " + base);
            // host+port only: nothing between the authority and the trailing slash.
            assertFalse(base.replaceFirst("^https?://", "").replaceFirst("/$", "").contains("/"),
                    "base url must not contain a context path: " + base);
        }
        for (String path : List.of(defaults.getOtp().getGeneratePath(),
                defaults.getOtp().getVerifyPath(),
                defaults.getOtp().getResendPath())) {
            assertFalse(path.startsWith("/"), "path must not start with a slash: " + path);
        }
    }
}
