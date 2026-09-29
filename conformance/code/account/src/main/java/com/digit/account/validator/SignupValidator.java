package com.digit.account.validator;

import com.digit.account.constants.Constants;
import com.digit.account.model.SignupResponses.SignupResendRequest;
import com.digit.account.model.SignupResponses.SignupVerifyRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Signup validation. Mirrors Go internal/validator/signup_validator.go. */
public final class SignupValidator {
    private SignupValidator() {}

    private static final Pattern OTP_REGEX = Pattern.compile("^[0-9]+$");

    private static int blen(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    public static List<String> validateSignupVerifyRequest(SignupVerifyRequest req) {
        List<String> errs = new ArrayList<>();
        errs.addAll(validateReferenceId(req.getReferenceId()));
        errs.addAll(validateOtp(req.getOtp()));
        errs.addAll(validatePurpose(req.getPurpose()));
        return errs;
    }

    public static List<String> validateSignupResendRequest(SignupResendRequest req) {
        return validateReferenceId(req.getReferenceId());
    }

    private static List<String> validateReferenceId(String id) {
        List<String> e = new ArrayList<>();
        if (id == null || id.isEmpty()) {
            e.add("referenceId is required");
            return e;
        }
        if (blen(id) < Constants.MIN_REFERENCE_ID_LENGTH) {
            e.add(String.format("referenceId must be at least %d characters (got %d)",
                    Constants.MIN_REFERENCE_ID_LENGTH, blen(id)));
            return e;
        }
        if (blen(id) > Constants.MAX_REFERENCE_ID_LENGTH) {
            e.add(String.format("referenceId must be at most %d characters (got %d)",
                    Constants.MAX_REFERENCE_ID_LENGTH, blen(id)));
        }
        return e;
    }

    private static List<String> validateOtp(String otp) {
        List<String> e = new ArrayList<>();
        if (otp == null || otp.isEmpty()) {
            e.add("otp is required");
            return e;
        }
        if (blen(otp) < Constants.MIN_OTP_LENGTH) {
            e.add(String.format("otp must be at least %d characters (got %d)",
                    Constants.MIN_OTP_LENGTH, blen(otp)));
            return e;
        }
        if (blen(otp) > Constants.MAX_OTP_LENGTH) {
            e.add(String.format("otp must be at most %d characters (got %d)",
                    Constants.MAX_OTP_LENGTH, blen(otp)));
            return e;
        }
        if (!OTP_REGEX.matcher(otp).matches()) {
            e.add("otp must contain only digits");
        }
        return e;
    }

    private static List<String> validatePurpose(String purpose) {
        List<String> e = new ArrayList<>();
        if (purpose == null || purpose.isEmpty()) {
            e.add("purpose is required");
            return e;
        }
        if (!purpose.equals(Constants.OTP_PURPOSE_REGISTRATION)) {
            e.add(String.format("purpose must be %s", TenantValidator.quote(Constants.OTP_PURPOSE_REGISTRATION)));
        }
        return e;
    }
}
