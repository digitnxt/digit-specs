package com.digit.account.clients.notification;

import com.digit.account.config.AccountProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Emails a newly provisioned tenant admin the temporary password the service generated for them,
 * via the notify service.
 *
 * <p>The notification config is owned by the configured platform tenant rather than by the tenant
 * being created: notify resolves a config with a strict
 * {@code WHERE tenant_id = ? AND template_code = ? AND is_active} and has no server-side fallback,
 * so a per-tenant copy would have to be written for every tenant before its first email could
 * render. One platform-tenant config avoids that entirely.
 *
 * <p>This client only references the config by code and supplies the payload it renders from; the
 * content itself belongs to notify. Keeping a copy of the body here would mean the wording could
 * not be changed without a redeploy, and the two copies would diverge the moment either was edited.
 *
 * <p>Payload keys the config's {@code payloadBindings} read with JsonPath: {@code tenantCode},
 * {@code tenantName}, {@code email}, {@code password}, and {@code loginUrls} — an ordered
 * label-to-url map, bound per entry as {@code $.loginUrls.admin} and so on. notify renders flat
 * {@code {{placeholder}}} only, so each url a template shows needs its own binding; the nesting is
 * flattened there rather than here, which is why this payload is unchanged from the shape the
 * notification service was sent.
 *
 * <p>Note that notify answers 202 for any request it accepted, including ones where nothing was
 * delivered, so success is read from the EMAIL channel status in the body rather than the code.
 */
public class NotificationClient {

    private final AccountProperties.Notification config;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public NotificationClient(AccountProperties.Notification config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        int timeout = config.getTimeoutSeconds() <= 0 ? 10 : config.getTimeoutSeconds();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout)).build();
    }

    /**
     * Sends the temporary password to the tenant's admin email. Returns whether it was actually
     * sent; throws on failure so the caller can decide whether a failed email should fail the
     * request.
     */
    public boolean sendTempPassword(String tenantCode, String tenantName, String email,
                                    String tempPassword, Map<String, String> loginUrls, String clientId) {
        // Gated here rather than by omitting the bean, so the caller needs no null check. Returns
        // false rather than throwing: being switched off is not a failure, but the admin still did
        // not receive the password, and the caller reports that distinction to its own client.
        if (!config.isEnabled()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantCode", tenantCode);
        payload.put("tenantName", tenantName);
        payload.put("email", email);
        payload.put("password", tempPassword);
        // Never null: a binding pointing at a missing path fails the render outright rather than
        // leaving a blank in the message.
        payload.put("loginUrls", loginUrls == null ? Map.of() : loginUrls);

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("email", email);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateCode", config.getTempPasswordTemplateCode());
        body.put("recipient", recipient);
        body.put("payload", payload);

        String url = config.getBaseUrl() + config.getNotifySendPath();
        Resp r = post(url, body, clientId);
        if (isSuccess(r.status)) {
            requireEmailDispatched(r);
            return true;
        }
        // Named explicitly because it is the one failure an operator fixes rather than retries: the
        // config is provisioned in notify, not by this client.
        if (isConfigNotFound(r.status, r.body)) {
            throw new RuntimeException("notify has no notification config \""
                    + config.getTempPasswordTemplateCode() + "\" for tenant \""
                    + config.getPlatformTenant() + "\"; create it there before tenants can be "
                    + "emailed their generated password (" + statusText(r) + ")");
        }
        throw new RuntimeException("notify email failed: " + statusText(r));
    }

    /**
     * notify reports each channel separately and still answers 202 when none of them went out, so the
     * EMAIL entry has to be found and checked.
     */
    private void requireEmailDispatched(Resp r) {
        JsonNode node;
        try {
            node = objectMapper.readTree(r.body);
        } catch (Exception e) {
            throw new RuntimeException("notify returned an unreadable response: " + e.getMessage(), e);
        }
        JsonNode channels = node == null ? null : node.get("channels");
        if (channels == null || !channels.isArray()) {
            throw new RuntimeException("notify returned no channel statuses");
        }
        for (JsonNode c : channels) {
            if (!"EMAIL".equalsIgnoreCase(text(c, "channel"))) {
                continue;
            }
            if ("DISPATCHED".equalsIgnoreCase(text(c, "status"))) {
                return;
            }
            String reason = text(c, "reason");
            throw new RuntimeException("notify did not send the temporary-password email: "
                    + text(c, "status") + (reason.isEmpty() ? "" : " - " + reason));
        }
        throw new RuntimeException("notify reported no EMAIL status for config \""
                + config.getTempPasswordTemplateCode() + "\"; the email channel is not enabled on it");
    }

    private Resp post(String url, Map<String, Object> body, String clientId) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new RuntimeException("failed to marshal notify payload: " + e.getMessage(), e);
        }
        int timeout = config.getTimeoutSeconds() <= 0 ? 10 : config.getTimeoutSeconds();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                // The config and the send are both scoped to the platform tenant, never to the
                // tenant being created — that tenant owns no config. Sent verbatim: notify matches
                // tenant_id exactly, so "default" and "DEFAULT" are different tenants and only the
                // lower-case one holds the shared configs.
                .header("X-Tenant-ID", config.getPlatformTenant())
                .POST(HttpRequest.BodyPublishers.ofByteArray(json));
        if (clientId != null && !clientId.isBlank()) {
            rb.header("X-User-Id", clientId);
        }
        try {
            HttpResponse<byte[]> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Resp(resp.statusCode(), resp.body());
        } catch (Exception e) {
            throw new RuntimeException("failed to reach notify: " + cause(e), e);
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    /** notify answers 404 with {@code code: "NOT_FOUND"} when the tenant owns no such config. */
    private boolean isConfigNotFound(int status, byte[] body) {
        if (status == 404) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode arr = node != null && node.isArray() ? node.get(0) : node;
            if (arr == null) {
                return false;
            }
            JsonNode code = arr.get("code");
            return code != null && code.asText("").toUpperCase(Locale.ROOT).contains("NOT_FOUND");
        } catch (Exception e) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String statusText(Resp r) {
        String b = r.body == null ? "" : new String(r.body, StandardCharsets.UTF_8);
        return "status " + r.status + (b.isEmpty() ? "" : ", body: " + b);
    }

    private static String cause(Throwable t) {
        if (t == null) {
            return "";
        }
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getName() : m;
    }

    private record Resp(int status, byte[] body) {}
}
