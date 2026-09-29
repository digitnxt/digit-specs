package org.digit.notify.provider.smscountry;

public class SmsCountryProviderConfig {

    private final String username;
    private final String password;
    private final String senderId;
    private final String apiUrl;

    public SmsCountryProviderConfig() {
        this.username = require("SMSCOUNTRY_USERNAME");
        this.password = require("SMSCOUNTRY_PASSWORD");
        this.apiUrl   = require("SMSCOUNTRY_API_URL");
        // Optional: left unset, the gateway applies the header registered against the
        // account. Requiring it would only force a made-up value, and an unregistered
        // header is dropped by the carrier rather than rejected by the API.
        this.senderId = System.getenv("SMSCOUNTRY_SENDER_ID");
    }

    public String getApiUrl() { return apiUrl; }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException(key + " env var is required");
        return v;
    }

    public String getUsername() { return username; }
    public String getPassword() { return password; }

    /** Null or blank when the account default should apply. */
    public String getSenderId() { return senderId; }

    public boolean hasSenderId() {
        return senderId != null && !senderId.isBlank();
    }
}