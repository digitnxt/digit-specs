package org.digit.notify.provider.gmail;

public class GmailProviderConfig {

    private final String fromAddress;
    private final String appPassword;
    private final String fromName;

    public GmailProviderConfig() {
        this.fromAddress = require("GMAIL_FROM_ADDRESS");
        this.appPassword = require("GMAIL_APP_PASSWORD");
        // Optional display name. Without it the recipient sees the bare address.
        this.fromName = System.getenv("GMAIL_FROM_NAME");
    }

    private String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException(key + " env var is required");
        return v;
    }

    public String getFromAddress() { return fromAddress; }
    public String getAppPassword()  { return appPassword; }

    /** Null or blank when the address should be shown on its own. */
    public String getFromName() { return fromName; }

    public boolean hasFromName() {
        return fromName != null && !fromName.isBlank();
    }
}
