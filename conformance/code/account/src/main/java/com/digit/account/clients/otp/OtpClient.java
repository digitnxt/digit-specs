package com.digit.account.clients.otp;

import com.digit.account.config.AccountProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Account-facing facade over the OTP microservice. {@code tenantID} is bound (reserved platform
 * tenant for pre-tenant signup flows); {@code purpose} is per-call. Mirrors Go
 * internal/clients/otp/client.go.
 */
public class OtpClient {

    public static class GenerateResponse {
        public String referenceId;
        public int expiresIn;
        public int cooldownSeconds;
    }

    public static class VerifyResponse {
        public boolean verified;
        public String purpose;
    }

    public static class ResendResponse {
        public String referenceId;
        public int expiresIn;
        public int cooldownSeconds;
        public String purpose;
    }

    private final AccountProperties.Otp config;
    private final String tenantId;
    private final int timeoutSeconds;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public OtpClient(AccountProperties.Otp config, ObjectMapper objectMapper) {
        int timeout = config.getTimeoutSeconds() <= 0 ? 5 : config.getTimeoutSeconds();
        this.config = config;
        this.tenantId = config.getPlatformTenant();
        this.timeoutSeconds = timeout;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .build();
    }

    public GenerateResponse generate(String destination, String destinationType, String purpose,
                                     Map<String, Object> metadata) {
        String identifier = destination;
        if (destinationType != null && !destinationType.isEmpty()) {
            identifier = destinationType + ":" + destination;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identifier", identifier);
        body.put("purpose", purpose);
        body.put("metadata", metadata);
        return doPost(config.getGeneratePath(), body, GenerateResponse.class);
    }

    public VerifyResponse verify(String referenceId, String otp, String purpose) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceId", referenceId);
        body.put("purpose", purpose);
        body.put("otp", otp);
        return doPost(config.getVerifyPath(), body, VerifyResponse.class);
    }

    public ResendResponse resend(String referenceId, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceId", referenceId);
        body.put("metadata", metadata);
        return doPost(config.getResendPath(), body, ResendResponse.class);
    }

    // OTP business-error codes, keyed by the upstream HTTP status. The controllers branch on these
    // (via CustomException.getCode()) to render per-endpoint messages. Mirrors Go's APIError sentinels.
    public static final String CODE_LOCKED = "OTP_LOCKED";              // 423
    public static final String CODE_EXPIRED = "OTP_EXPIRED";            // 410
    public static final String CODE_INVALID_VALUE = "OTP_INVALID";      // 422
    public static final String CODE_NOT_FOUND = "OTP_NOT_FOUND";        // 404
    public static final String CODE_RATE_LIMITED = "OTP_RATE_LIMITED";  // 429
    public static final String CODE_BAD_REQUEST = "OTP_BAD_REQUEST";    // 400

    /** Maps a 4xx OTP status to its business error code, or null for non-business statuses (5xx/other). */
    public static String businessCode(int status) {
        return switch (status) {
            case 423 -> CODE_LOCKED;
            case 410 -> CODE_EXPIRED;
            case 422 -> CODE_INVALID_VALUE;
            case 404 -> CODE_NOT_FOUND;
            case 429 -> CODE_RATE_LIMITED;
            case 400 -> CODE_BAD_REQUEST;
            default -> null;
        };
    }

    private <T> T doPost(String path, Object reqBody, Class<T> type) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(reqBody);
        } catch (Exception e) {
            // marshal failure is an internal/IO error -> propagate to tracer 500 handler
            throw new RuntimeException("failed to marshal request: " + e.getMessage(), e);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            // genuine downstream/network failure -> propagate to tracer 500 handler
            throw new RuntimeException("OTP service request failed: " + e.getMessage(), e);
        }
        int status = resp.statusCode();
        byte[] body = resp.body();
        if (status >= 400) {
            String msg = body == null ? "" : new String(body);
            String detail = "OTP service error (" + status + "): " + msg;
            String code = businessCode(status);
            if (code != null) {
                // expected business error -> CustomException (HTTP 400 via tracer ExceptionAdvice)
                throw new CustomException(code, detail);
            }
            // upstream 5xx / unknown -> propagate to tracer 500 handler
            throw new RuntimeException(detail);
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            // response parse failure is an internal/IO error -> propagate to tracer 500 handler
            throw new RuntimeException("failed to unmarshal response: " + e.getMessage(), e);
        }
    }
}
