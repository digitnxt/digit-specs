package com.digit.individual.service;

import com.digit.individual.client.VaultClient;
import com.digit.individual.config.IndividualProperties;
import com.digit.individual.constants.ErrorCodes;
import com.digit.individual.model.Identifier;
import com.digit.individual.model.Individual;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * PII encryption/decryption. Mirrors Go internal/service/encryption_service.go.
 *
 * <p>Vault integration is config-gated ({@code individual.vault.enabled}). When disabled, encryption/
 * decryption are no-ops but the mobile number is still HMAC-SHA256 hashed into {@code hashedmobilenumber}
 * for indexed search — exactly the Go disabled path. When enabled, the Vault Transit engine encrypts
 * mobileNumber / altContactNumber / AADHAAR identifierIds (stored as {@code vault:v1:...} ciphertext)
 * while the keyed HMAC hash of the plaintext mobile number is stored alongside for exact-match search;
 * reads decrypt back to plaintext.
 */
@Service
public class EncryptionService {

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

    /** Encrypts PII fields in place. Mirrors Go encryptionService.EncryptIndividual. */
    public void encryptIndividual(Individual ind) {
        if (!config.isEnabled()) {
            // Still hash mobile number for search even without encryption.
            if (ind.getMobileNumber() != null && !ind.getMobileNumber().isEmpty()) {
                ind.setHashedMobileNumber(hashMobileNumber(ind.getMobileNumber()));
            }
            return;
        }

        // Encrypt mobile number (hash of the plaintext is retained for search).
        // Vault transit failures are genuine infra errors; let them propagate to the
        // tracer's generic 500 handler rather than masquerading as a business error.
        if (ind.getMobileNumber() != null && !ind.getMobileNumber().isEmpty()) {
            String plaintext = ind.getMobileNumber();
            String hash = hashMobileNumber(plaintext);
            String encrypted = vaultClient.encrypt(plaintext, ind.getTenantId());
            ind.setMobileNumber(encrypted);
            ind.setHashedMobileNumber(hash);
        }

        // Encrypt alt contact number.
        if (ind.getAltContactNumber() != null && !ind.getAltContactNumber().isEmpty()) {
            String encrypted = vaultClient.encrypt(ind.getAltContactNumber(), ind.getTenantId());
            ind.setAltContactNumber(encrypted);
        }

        // Encrypt identifiers (especially AADHAAR).
        List<Identifier> identifiers = ind.getIdentifiers();
        if (identifiers != null) {
            for (Identifier id : identifiers) {
                if (ErrorCodes.IDENTIFIER_TYPE_AADHAAR.equals(id.getIdentifierType())
                        && id.getIdentifierId() != null && !id.getIdentifierId().isEmpty()) {
                    String encrypted = vaultClient.encrypt(id.getIdentifierId(), ind.getTenantId());
                    id.setIdentifierId(encrypted);
                }
            }
        }
    }

    /** Decrypts PII fields in place. Mirrors Go encryptionService.DecryptIndividual. */
    public void decryptIndividual(Individual ind) {
        if (!config.isEnabled()) {
            return;
        }

        // Vault transit failures are genuine infra errors; let them propagate (tracer 500 handler).
        if (ind.getMobileNumber() != null && !ind.getMobileNumber().isEmpty()) {
            ind.setMobileNumber(vaultClient.decrypt(ind.getMobileNumber(), ind.getTenantId()));
        }

        if (ind.getAltContactNumber() != null && !ind.getAltContactNumber().isEmpty()) {
            ind.setAltContactNumber(vaultClient.decrypt(ind.getAltContactNumber(), ind.getTenantId()));
        }

        List<Identifier> identifiers = ind.getIdentifiers();
        if (identifiers != null) {
            for (Identifier id : identifiers) {
                if (ErrorCodes.IDENTIFIER_TYPE_AADHAAR.equals(id.getIdentifierType())
                        && id.getIdentifierId() != null && !id.getIdentifierId().isEmpty()) {
                    id.setIdentifierId(vaultClient.decrypt(id.getIdentifierId(), ind.getTenantId()));
                }
            }
        }
    }

    /**
     * Decrypts multiple individuals. Mirrors Go encryptionService.DecryptIndividuals, which returns on
     * the first row error; the search caller catches and logs it so the search still succeeds.
     */
    public void decryptIndividuals(List<Individual> individuals) {
        for (Individual ind : individuals) {
            decryptIndividual(ind);
        }
    }

    public String hashMobileNumber(String mobileNumber) {
        return HashUtil.hashMobileNumber(hmacSecret, mobileNumber);
    }
}
