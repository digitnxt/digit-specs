package com.digit.account.util;

import java.util.Locale;

/** Code generation. Mirrors Go internal/util/codegen.go. */
public final class CodeGen {
    private CodeGen() {}

    /**
     * Derives a tenant code from a name: strip surrounding whitespace, remove interior spaces,
     * uppercase. Nothing else is removed, so a hyphen or underscore in the name survives into the
     * code — {@code josep-parshad} yields {@code JOSEP-PARSHAD}, and tenants of exactly that shape
     * already exist.
     *
     * <p>Deliberately not "drop everything outside [A-Z0-9]". Consumers derive this same code from
     * the same name to predict a tenant's identity, so the rule here is a contract, not an
     * implementation detail: narrowing it re-derives a different code for names already in use and
     * leaves those callers looking for a tenant that no longer answers to that identity.
     *
     * <p>Characters this leaves in that would not survive as a realm or schema name are caught
     * afterwards by {@code validateDerivedCode}, which fails the request rather than provisioning a
     * realm whose name cannot be addressed.
     */
    public static String generateCodeFromName(String name) {
        if (name == null) {
            return "";
        }
        // Locale.ROOT: the default locale would map "I" to a dotless "ı" under tr-TR, making the
        // derived code depend on the JVM's locale.
        return name.strip().replace(" ", "").toUpperCase(Locale.ROOT);
    }
}