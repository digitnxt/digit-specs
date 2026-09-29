package com.digit.account.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Signup request/response DTOs. Mirrors Go models/signup.go. */
public final class SignupResponses {
    private SignupResponses() {}

    /** Payload for {@code POST /tenants/registrations/verify}. */
    public static class SignupVerifyRequest {
        private String referenceId;
        private String otp;
        private String purpose;
        public String getReferenceId() { return referenceId; }
        public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
        public String getOtp() { return otp; }
        public void setOtp(String otp) { this.otp = otp; }
        public String getPurpose() { return purpose; }
        public void setPurpose(String purpose) { this.purpose = purpose; }
    }

    /** Payload for {@code POST /tenants/registrations/resend}. */
    public static class SignupResendRequest {
        private String referenceId;
        public String getReferenceId() { return referenceId; }
        public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
    }

    @JsonPropertyOrder({"referenceId", "expiresIn", "cooldownSeconds"})
    public static class SignupInitiateResponse {
        private String referenceId;
        private int expiresIn;
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int cooldownSeconds;
        public String getReferenceId() { return referenceId; }
        public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
        public int getExpiresIn() { return expiresIn; }
        public void setExpiresIn(int expiresIn) { this.expiresIn = expiresIn; }
        public int getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    }

    @JsonPropertyOrder({"referenceId", "expiresIn", "cooldownSeconds"})
    public static class SignupResendResponse {
        private String referenceId;
        private int expiresIn;
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int cooldownSeconds;
        public String getReferenceId() { return referenceId; }
        public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
        public int getExpiresIn() { return expiresIn; }
        public void setExpiresIn(int expiresIn) { this.expiresIn = expiresIn; }
        public int getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    }
}
