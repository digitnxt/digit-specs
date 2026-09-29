package com.digit.individual.service;

import com.digit.individual.client.VaultClient;
import com.digit.individual.config.IndividualProperties;
import com.digit.individual.model.Identifier;
import com.digit.individual.model.Individual;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/** Every PII field of a call goes to Vault in one batch, and a Vault failure never leaks ciphertext. */
class EncryptionServiceTest {

    private static IndividualProperties props(boolean vaultEnabled) {
        IndividualProperties props = new IndividualProperties();
        props.getVault().setEnabled(vaultEnabled);
        props.setHmacSecret("pepper");
        return props;
    }

    private static Identifier identifier(String type, String value) {
        Identifier id = new Identifier();
        id.setIdentifierType(type);
        id.setIdentifierId(value);
        return id;
    }

    private static Individual individual(String mobile, String alt, String aadhaar) {
        Individual ind = new Individual();
        ind.setTenantId("t1");
        ind.setMobileNumber(mobile);
        ind.setAltContactNumber(alt);
        List<Identifier> ids = new ArrayList<>();
        ids.add(identifier("AADHAAR", aadhaar));
        ids.add(identifier("PAN", "PAN-1"));
        ind.setIdentifiers(ids);
        return ind;
    }

    /** Stands in for Vault: encrypt prefixes, decrypt strips, one call per invocation. */
    private static VaultClient fakeVault() {
        VaultClient vault = Mockito.mock(VaultClient.class);
        Mockito.when(vault.encryptBatch(any(), anyString())).thenAnswer(inv ->
                inv.<List<String>>getArgument(0).stream().map(v -> "vault:v1:" + v).toList());
        Mockito.when(vault.decryptBatch(any(), anyString())).thenAnswer(inv ->
                inv.<List<String>>getArgument(0).stream().map(v -> v.substring("vault:v1:".length())).toList());
        return vault;
    }

    @Test
    void decryptPage_isOneVaultCall_andEachValueLandsInItsOwnField() {
        VaultClient vault = fakeVault();
        EncryptionService svc = new EncryptionService(props(true), vault);
        List<Individual> page = List.of(
                individual("vault:v1:111", "vault:v1:alt1", "vault:v1:A1"),
                individual("vault:v1:222", null, "vault:v1:A2"),
                individual("vault:v1:333", "vault:v1:alt3", "vault:v1:A3"));

        svc.decryptIndividuals(page);

        Mockito.verify(vault, Mockito.times(1)).decryptBatch(any(), Mockito.eq("t1"));
        assertEquals("111", page.get(0).getMobileNumber());
        assertEquals("alt1", page.get(0).getAltContactNumber());
        assertEquals("A1", page.get(0).getIdentifiers().get(0).getIdentifierId());
        assertEquals("222", page.get(1).getMobileNumber());
        assertEquals(null, page.get(1).getAltContactNumber());
        assertEquals("A3", page.get(2).getIdentifiers().get(0).getIdentifierId());
        // Only AADHAAR is encrypted; other identifier types pass through untouched.
        assertEquals("PAN-1", page.get(2).getIdentifiers().get(1).getIdentifierId());
    }

    @Test
    void encrypt_isOneVaultCall_hashesPlaintext_andRestoresPlaintext() {
        VaultClient vault = fakeVault();
        EncryptionService svc = new EncryptionService(props(true), vault);
        Individual ind = individual("9800000001", "9800000002", "123412341234");

        Runnable restore = svc.encryptIndividual(ind);

        Mockito.verify(vault, Mockito.times(1)).encryptBatch(
                Mockito.eq(List.of("9800000001", "9800000002", "123412341234")), Mockito.eq("t1"));
        assertEquals("vault:v1:9800000001", ind.getMobileNumber());
        assertEquals(svc.hashMobileNumber("9800000001"), ind.getHashedMobileNumber());

        restore.run();
        assertEquals("9800000001", ind.getMobileNumber());
        assertEquals("9800000002", ind.getAltContactNumber());
        assertEquals("123412341234", ind.getIdentifiers().get(0).getIdentifierId());
        Mockito.verify(vault, Mockito.never()).decryptBatch(any(), anyString());
    }

    @Test
    void vaultFailure_is502_andLeavesNothingHalfDecrypted() {
        VaultClient vault = Mockito.mock(VaultClient.class);
        Mockito.when(vault.decryptBatch(any(), anyString())).thenThrow(new RuntimeException("vault returned status 500"));
        EncryptionService svc = new EncryptionService(props(true), vault);
        Individual ind = individual("vault:v1:111", null, null);

        CustomException ex = assertThrows(CustomException.class, () -> svc.decryptIndividual(ind));

        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("vault: failed to decrypt personal data", ex.getMessage());
        assertFalse(ex.getMessage().contains("status 500"), "the raw Vault cause must stay in the server log");
    }

    @Test
    void vaultDisabled_makesNoCalls_butStillHashesTheMobile() {
        VaultClient vault = Mockito.mock(VaultClient.class);
        EncryptionService svc = new EncryptionService(props(false), vault);
        Individual ind = individual("9800000001", null, "123412341234");

        svc.encryptIndividual(ind).run();
        svc.decryptIndividual(ind);

        Mockito.verifyNoInteractions(vault);
        assertEquals("9800000001", ind.getMobileNumber());
        assertEquals(svc.hashMobileNumber("9800000001"), ind.getHashedMobileNumber());
    }
}
