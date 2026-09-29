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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Encrypts every plaintext in one Transit request ({@code batch_input}); the ciphertexts come back
     * in input order. The caller passes only values that need encrypting. Returns the input unchanged
     * when Vault is disabled, and makes no call for an empty list.
     */
    public List<String> encryptBatch(List<String> plaintexts, String key) {
        if (!enabled || plaintexts.isEmpty()) {
            return plaintexts;
        }
        List<Map<String, Object>> items = new ArrayList<>(plaintexts.size());
        for (String plaintext : plaintexts) {
            items.add(Map.of("plaintext",
                    Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8))));
        }
        List<String> ciphertexts = new ArrayList<>(plaintexts.size());
        for (JsonNode result : batch("/v1/transit/encrypt/" + key, items, "encrypt")) {
            JsonNode ciphertext = result.get("ciphertext");
            if (ciphertext == null || ciphertext.asString().isEmpty()) {
                throw new RuntimeException("empty ciphertext in response");
            }
            ciphertexts.add(ciphertext.asString());
        }
        return ciphertexts;
    }

    /**
     * Decrypts every {@code vault:v1:} ciphertext in one Transit request; the plaintexts come back in
     * input order. The caller passes only encrypted values. Returns the input unchanged when Vault is
     * disabled, and makes no call for an empty list.
     */
    public List<String> decryptBatch(List<String> ciphertexts, String key) {
        if (!enabled || ciphertexts.isEmpty()) {
            return ciphertexts;
        }
        List<Map<String, Object>> items = new ArrayList<>(ciphertexts.size());
        for (String ciphertext : ciphertexts) {
            items.add(Map.of("ciphertext", ciphertext));
        }
        List<String> plaintexts = new ArrayList<>(ciphertexts.size());
        for (JsonNode result : batch("/v1/transit/decrypt/" + key, items, "decrypt")) {
            JsonNode plaintext = result.get("plaintext");
            if (plaintext == null || plaintext.asString().isEmpty()) {
                throw new RuntimeException("empty plaintext in response");
            }
            try {
                plaintexts.add(new String(Base64.getDecoder().decode(plaintext.asString()), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("failed to decode plaintext: " + e.getMessage());
            }
        }
        return plaintexts;
    }

    /**
     * Sends one batch request and returns its {@code batch_results}, one per input item and in input
     * order. Vault answers a batch containing any failed item with a non-200 status; an item-level
     * {@code error} is still checked in case a partial failure is reported with 200.
     */
    private List<JsonNode> batch(String path, List<Map<String, Object>> items, String op) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("batch_input", items);
        JsonNode results = call(baseAddress() + path, requestBody, op).get("batch_results");
        if (results == null || !results.isArray() || results.size() != items.size()) {
            throw new RuntimeException("vault " + op + ": expected " + items.size() + " batch results");
        }
        List<JsonNode> out = new ArrayList<>(items.size());
        for (JsonNode result : results) {
            JsonNode error = result.get("error");
            if (error != null && !error.asString().isEmpty()) {
                throw new RuntimeException("vault " + op + " item failed: " + error.asString());
            }
            out.add(result);
        }
        return out;
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
