package com.digit.account.util;

import com.digit.account.model.TenantEntity;
import com.digit.account.validator.TenantValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The derivation rule is a contract other services re-implement to predict a tenant's code, so it is
 * pinned here rather than left to whatever the implementation happens to do.
 */
class CodeDerivationTest {

    @Test
    void stripsSpacesAndUppercases() {
        assertEquals("PUNEMUNICIPAL", CodeGen.generateCodeFromName("Pune Municipal"));
        assertEquals("CITYA", CodeGen.generateCodeFromName("  City A  "));
    }

    @Test
    void keepsHyphenAndUnderscore() {
        // Codes of exactly this shape are already live; deriving anything else orphans them.
        assertEquals("JOSEP-PARSHAD", CodeGen.generateCodeFromName("josep-parshad"));
        assertEquals("JOSEP_PARSHAD", CodeGen.generateCodeFromName("josep_parshad"));
    }

    @Test
    void isLocaleIndependent() {
        // Under tr-TR the default locale maps "i" to a dotless "ı", which is not [A-Z0-9].
        assertEquals("INDIA", CodeGen.generateCodeFromName("india"));
    }

    @Test
    void aNameWithNoUsableCharactersDerivesEmpty() {
        assertEquals("", CodeGen.generateCodeFromName("   "));
        assertEquals("", CodeGen.generateCodeFromName(null));
    }

    // ------------------------------------------------------------ what validation then accepts

    private static List<String> validate(String name) {
        String code = CodeGen.generateCodeFromName(name);
        return TenantValidator.validateDerivedCode(code, name);
    }

    @Test
    void derivedCodesWithHyphenOrUnderscoreValidate() {
        assertTrue(validate("josep-parshad").isEmpty(), validate("josep-parshad").toString());
        assertTrue(validate("josep_parshad").isEmpty(), validate("josep_parshad").toString());
        assertTrue(validate("Pune Municipal").isEmpty());
    }

    @Test
    void derivedCodesCarryingUnusableCharactersAreRejected() {
        // Left in by derivation on purpose, then refused here — better than provisioning a realm
        // whose name contains a URL fragment delimiter or a quote.
        for (String name : new String[]{"Nagar Palika #2", "St. Mary's Trust", "a/b", "मुंबई"}) {
            assertFalse(validate(name).isEmpty(), "expected rejection for " + name);
        }
    }

    @Test
    void anExistingHyphenatedCodeCanStillBeUpdated() {
        // update() carries the stored code through validateTenantEntity; a code this rejects could
        // never be updated again, which is how the two live tenants got frozen.
        TenantEntity e = new TenantEntity();
        e.setId("id-1");
        e.setCode("JOSEP-PARSHAD");
        e.setName("josep-parshad");
        e.setEmail("a@example.com");
        assertTrue(TenantValidator.validateTenantEntity(e).isEmpty(),
                TenantValidator.validateTenantEntity(e).toString());
    }
}