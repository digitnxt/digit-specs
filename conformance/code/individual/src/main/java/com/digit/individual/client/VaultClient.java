package com.digit.individual.client;

import com.digit.individual.config.IndividualProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vault Transit client for PII encryption/decryption. Mirrors Go internal/clients/vault_client.go.
 *
 * <p>Calls the Vault HTTP API directly (matching the Go service, which used direct HTTP calls as a
 * workaround for the vault-client-go library): POST {address}/v1/transit/encrypt/{key} with a
 * base64-encoded plaintext, and POST {address}/v1/transit/decrypt/{key} with the {@code vault:v1:...}
 * ciphertext (response plaintext is base64-decoded). When Vault is disabled the calls are bypassed
 * and the value is returned unchanged, exactly as in Go.
 */
@Component
public class VaultClient {

    private static final Logger log = LoggerFactory.getLogger(VaultClient.class);

    private final IndividualProperties.Vault config;
    private final boolean enabled;
    private final HttpClient httpClient;
    private final JsonMapper mapper;
    private final VaultAuth auth;

    public VaultClient(IndividualProperties props) {
        this.config = props.getVault();
        this.enabled = config.isEnabled();
        this.mapper = JsonMapper.builder().build();
        // Matches Go vault_client.go httpClient timeout of 10s.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (!enabled) {
            this.auth = null;
            log.info("Vault disabled - encryption/decryption will be bypassed");
        } else {
            this.auth = new VaultAuth(config.getAddress(), config.getRoleId(), config.getSecretId(), httpClient, mapper);
            this.auth.login();
            log.info("Vault client initialized (AppRole) (address={})", config.getAddress());
        }
    }

    /** Vault address with any trailing slash removed, so transit paths don't produce a double slash. */
    private String baseAddress() {
        String addr = config.getAddress();
        if (addr == null) {
            return "";
        }
        int end = addr.length();
        while (end > 0 && addr.charAt(end - 1) == '/') {
            end--;
        }
        return addr.substring(0, end);
    }

    /** Encrypts plaintext via Vault Transit. Returns plaintext unchanged when disabled/empty/already-encrypted. */
    public String encrypt(String plaintext, String key) {
        if (!enabled) {
            return plaintext;
        }
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        // Already encrypted (vault:v1: prefix) - return as-is.
        if (plaintext.startsWith("vault:v1:")) {
            return plaintext;
        }

        String encoded = Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("plaintext", encoded);

        String url = baseAddress() + "/v1/transit/encrypt/" + key;
        JsonNode data = call(url, requestBody, "encrypt");

        JsonNode ciphertextNode = data.get("ciphertext");
        String ciphertext = ciphertextNode == null ? null : ciphertextNode.asString();
        if (ciphertext == null || ciphertext.isEmpty()) {
            throw new RuntimeException("empty ciphertext in response");
        }
        return ciphertext;
    }

    /** Decrypts ciphertext via Vault Transit. Returns ciphertext unchanged when disabled/empty/not-encrypted. */
    public String decrypt(String ciphertext, String key) {
        if (!enabled) {
            return ciphertext;
        }
        if (ciphertext == null || ciphertext.isEmpty()) {
            return "";
        }
        // Not encrypted (no vault:v1: prefix) - return as-is.
        if (!ciphertext.startsWith("vault:v1:")) {
            return ciphertext;
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("ciphertext", ciphertext);

        String url = baseAddress() + "/v1/transit/decrypt/" + key;
        JsonNode data = call(url, requestBody, "decrypt");

        JsonNode plaintextNode = data.get("plaintext");
        String plaintextB64 = plaintextNode == null ? null : plaintextNode.asString();
        if (plaintextB64 == null || plaintextB64.isEmpty()) {
            throw new RuntimeException("empty plaintext in response");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(plaintextB64);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("failed to decode plaintext: " + e.getMessage());
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private JsonNode call(String url, Map<String, Object> requestBody, String op) {
        String json;
        try {
            json = mapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new RuntimeException("failed to marshal request: " + e.getMessage());
        }

        HttpResponse<String> resp;
        try {
            auth.ensureToken();
            resp = send(url, json);
            // Token expired/revoked: re-login via AppRole and retry once.
            if (resp.statusCode() == 403) {
                auth.login();
                resp = send(url, json);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("failed to " + op + " data: " + e.getMessage());
        }

        if (resp.statusCode() != 200) {
            throw new RuntimeException("vault returned status " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new RuntimeException("failed to decode response: " + e.getMessage());
        }
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new RuntimeException("empty response from vault");
        }
        return data;
    }

    private HttpResponse<String> send(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Vault-Token", auth.token())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
