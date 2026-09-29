package org.digit.notify.provider.twilio;

public class TwilioWhatsAppProviderConfig {

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;

    public TwilioWhatsAppProviderConfig() {
        this.accountSid  = require("TWILIO_ACCOUNT_SID");
        this.authToken   = require("TWILIO_AUTH_TOKEN");
        this.fromNumber  = require("TWILIO_WHATSAPP_FROM");
    }

    private String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException(key + " env var is required");
        return v;
    }

    public String getAccountSid() { return accountSid; }
    public String getAuthToken()  { return authToken; }
    public String getFromNumber() { return fromNumber; }
}
