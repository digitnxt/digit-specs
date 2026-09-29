package com.digit.individual.service;

import com.digit.individual.client.VaultClient;
import com.digit.individual.config.IndividualProperties;
import com.digit.individual.constants.ErrorCodes;
import com.digit.individual.model.Identifier;
import com.digit.individual.model.Individual;
import org.digit.tracer.model.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * PII encryption/decryption. Mirrors Go internal/service/encryption_service.go.
 *
 * <p>Vault integration is config-gated ({@code individual.vault.enabled}). When disabled, encryption/
 * decryption are no-ops but the mobile number is still HMAC-SHA256 hashed into {@code hashedmobilenumber}
 * for indexed search — exactly the Go disabled path. When enabled, the Vault Transit engine encrypts
 * mobileNumber / altContactNumber / AADHAAR identifierIds (stored as {@code vault:v1:...} ciphertext)
 * while the keyed HMAC hash of the plaintext mobile number is stored alongside for exact-match search;
 * reads decrypt back to plaintext.
 *
 * <p>Every field of every individual in a call goes to Vault in one batch request. A Vault failure
 * fails the request as {@code DOWNSTREAM_ERROR} (502): a partly decrypted response would hand
 * ciphertext to the caller as if it were data.
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);
    private static final String CIPHERTEXT_PREFIX = "vault:v1:";

    private final IndividualProperties.Vault config;
    private final VaultClient vaultClient;
    private final byte[] hmacSecret;

    public EncryptionService(IndividualProperties props, VaultClient vaultClient) {
        this.config = props.getVault();
        this.vaultClient = vaultClient;
        this.hmacSecret = props.getHmacSecret().getBytes(StandardCharsets.UTF_8);
        // Fail closed: when Vault is on the mobile column is encrypted at rest, so the blind index
        // MUST be keyed — otherwise the (reversible) hash would defeat the encryption. A blank pepper
        // here is a deploy misconfiguration, not something to silently accept. Mirrors Go config.Validate.
        if (config.isEnabled() && props.getHmacSecret().isEmpty()) {
            throw new IllegalStateException(
                    "HMAC_SECRET is required when Vault is enabled (mobile-number blind index must be keyed)");
        }
    }

    /** PII values bound for one Vault batch, each with the setter that receives its result. */
    private static final class Batch {
        private final List<String> values = new ArrayList<>();
        private final List<Consumer<String>> targets = new ArrayList<>();

        void add(String value, Consumer<String> target) {
            values.add(value);
            targets.add(target);
        }

        void apply(List<String> results) {
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).accept(results.get(i));
            }
        }
    }

    /**
     * Encrypts PII fields in place, in one Vault call, and returns an action that puts the plaintext
     * back, so a response can be built from the same object without a decrypt round trip. Mirrors Go
     * encryptionService.EncryptIndividual.
     */
    public Runnable encryptIndividual(Individual ind) {
        // The hash of the plaintext mobile is kept for search, with or without encryption.
        if (!isEmpty(ind.getMobileNumber())) {
            ind.setHashedMobileNumber(hashMobileNumber(ind.getMobileNumber()));
        }
        if (!config.isEnabled()) {
            return () -> { };
        }

        Batch batch = new Batch();
        Batch restore = new Batch();
        if (needsEncryption(ind.getMobileNumber())) {
            batch.add(ind.getMobileNumber(), ind::setMobileNumber);
            restore.add(ind.getMobileNumber(), ind::setMobileNumber);
        }
        if (needsEncryption(ind.getAltContactNumber())) {
            batch.add(ind.getAltContactNumber(), ind::setAltContactNumber);
            restore.add(ind.getAltContactNumber(), ind::setAltContactNumber);
        }
        for (Identifier id : identifiers(ind)) {
            if (isAadhaar(id) && needsEncryption(id.getIdentifierId())) {
                batch.add(id.getIdentifierId(), id::setIdentifierId);
                restore.add(id.getIdentifierId(), id::setIdentifierId);
            }
        }
        if (batch.values.isEmpty()) {
            return () -> { };
        }

        try {
            batch.apply(vaultClient.encryptBatch(batch.values, ind.getTenantId()));
        } catch (RuntimeException e) {
            throw downstream("encrypt", ind.getTenantId(), e);
        }
        return () -> restore.apply(restore.values);
    }

    /** Decrypts PII fields in place. Mirrors Go encryptionService.DecryptIndividual. */
    public void decryptIndividual(Individual ind) {
        decryptIndividuals(List.of(ind));
    }

    /**
     * Decrypts the PII of every individual in one Vault call per tenant key (a request is tenant-
     * scoped, so in practice one call). Any failure fails the whole call; nothing is left half
     * decrypted in the response. Mirrors Go encryptionService.DecryptIndividuals.
     */
    public void decryptIndividuals(List<Individual> individuals) {
        if (!config.isEnabled()) {
            return;
        }
        Map<String, Batch> byTenant = new LinkedHashMap<>();
        for (Individual ind : individuals) {
            Batch batch = byTenant.computeIfAbsent(ind.getTenantId(), t -> new Batch());
            if (isCiphertext(ind.getMobileNumber())) {
                batch.add(ind.getMobileNumber(), ind::setMobileNumber);
            }
            if (isCiphertext(ind.getAltContactNumber())) {
                batch.add(ind.getAltContactNumber(), ind::setAltContactNumber);
            }
            for (Identifier id : identifiers(ind)) {
                if (isAadhaar(id) && isCiphertext(id.getIdentifierId())) {
                    batch.add(id.getIdentifierId(), id::setIdentifierId);
                }
            }
        }
        for (Map.Entry<String, Batch> e : byTenant.entrySet()) {
            Batch batch = e.getValue();
            if (batch.values.isEmpty()) {
                continue;
            }
            try {
                batch.apply(vaultClient.decryptBatch(batch.values, e.getKey()));
            } catch (RuntimeException ex) {
                throw downstream("decrypt", e.getKey(), ex);
            }
        }
    }

    public String hashMobileNumber(String mobileNumber) {
        return HashUtil.hashMobileNumber(hmacSecret, mobileNumber);
    }

    /** Logs the cause (never the values) and returns the client-facing 502. */
    private static CustomException downstream(String op, String tenantId, RuntimeException cause) {
        log.error("vault {} failed tenantId={}", op, tenantId, cause);
        return new CustomException(ErrorCodes.DOWNSTREAM, "vault: failed to " + op + " personal data",
                HttpStatus.BAD_GATEWAY);
    }

    private static List<Identifier> identifiers(Individual ind) {
        return ind.getIdentifiers() == null ? List.of() : ind.getIdentifiers();
    }

    private static boolean isAadhaar(Identifier id) {
        return ErrorCodes.IDENTIFIER_TYPE_AADHAAR.equals(id.getIdentifierType());
    }

    /** Non-empty and not already ciphertext. */
    private static boolean needsEncryption(String value) {
        return !isEmpty(value) && !value.startsWith(CIPHERTEXT_PREFIX);
    }

    private static boolean isCiphertext(String value) {
        return value != null && value.startsWith(CIPHERTEXT_PREFIX);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
