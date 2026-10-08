package com.digit.account.web;

import com.digit.account.clients.otp.OtpClient;
import com.digit.account.cache.SignupCache;
import com.digit.account.constants.Constants;
import com.digit.account.constants.Headers;
import com.digit.account.model.Mappers;
import com.digit.account.model.SignupInitiateRequest;
import com.digit.account.model.SignupResponses.SignupInitiateResponse;
import com.digit.account.model.SignupResponses.SignupResendRequest;
import com.digit.account.model.SignupResponses.SignupResendResponse;
import com.digit.account.model.SignupResponses.SignupVerifyRequest;
import com.digit.account.model.TenantCreateRequest;
import com.digit.account.model.TenantEntity;
import com.digit.account.model.TenantResponse;
import com.digit.account.model.TenantUpdateRequest;
import com.digit.account.service.TenantService;
import com.digit.account.util.CodeGen;
import com.digit.account.validator.SignupValidator;
import com.digit.account.validator.TenantValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.digit.tracer.model.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tenant CRUD + self-service signup endpoints. Mirrors Go internal/handlers/tenant_handler.go.
 *  Business/validation errors surface as the tracer's CustomException (HTTP 400 via ExceptionAdvice);
 *  genuine infra/downstream failures propagate to the tracer's generic 500 handler. */
@RestController
@RequestMapping("/v3")
public class TenantController {

    private static final Logger log = LoggerFactory.getLogger(TenantController.class);

    private final TenantService service;
    private final OtpClient otpClient;
    private final SignupCache signupCache;
    private final ObjectMapper objectMapper;

    public TenantController(TenantService service, OtpClient otpClient, SignupCache signupCache,
                            ObjectMapper objectMapper) {
        this.service = service;
        this.otpClient = otpClient;
        this.signupCache = signupCache;
        this.objectMapper = objectMapper;
    }

    // ---------- Tenants (admin) ----------

    @PostMapping("/tenants")
    public ResponseEntity<?> createTenant(
            @RequestHeader(value = Headers.CLIENT_ID, required = false) String clientId,
            @RequestHeader(value = Headers.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) byte[] body) {
        TenantCreateRequest req = ControllerSupport.parseBody(objectMapper, body, TenantCreateRequest.class);
        ControllerSupport.failIfValidation(TenantValidator.validateTenantCreateRequest(req));
        TenantResponse resp = service.create(req, clientId, requestId);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @GetMapping("/tenants")
    public ResponseEntity<?> listTenants(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "isActive", required = false) String isActive,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "size", required = false) String size) {
        List<String> errs = new java.util.ArrayList<>();
        errs.addAll(TenantValidator.validateOptionalTenantIdHeader(tenantId));
        // Folded into code rather than passed separately: there is no tenant id in this service, so
        // scoping the registry to one tenant is exactly a filter on its code.
        String scopedCode = TenantValidator.resolveTenantIdScope(tenantId, code, errs);
        TenantValidator.ListQueryParams p =
                TenantValidator.validateTenantListQuery(scopedCode, name, email, isActive, page, size, errs);
        ControllerSupport.failIfValidation(errs);
        return ResponseEntity.ok(service.list(p.code, p.name, p.email, p.isActive, p.page, p.size));
    }

    @PutMapping("/tenants/{id}")
    public ResponseEntity<?> updateTenant(
            @PathVariable("id") String id,
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = Headers.CLIENT_ID, required = false) String clientId,
            @RequestHeader(value = Headers.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) byte[] body) {
        ControllerSupport.failIfValidation(TenantValidator.validateTenantID(id));
        ControllerSupport.failIfValidation(TenantValidator.validateOptionalTenantIdHeader(tenantId));
        TenantUpdateRequest req = ControllerSupport.parseBody(objectMapper, body, TenantUpdateRequest.class);
        ControllerSupport.failIfValidation(TenantValidator.validateTenantUpdateRequest(req));
        return ResponseEntity.ok(service.update(id, req, clientId, requestId, tenantId));
    }

    @DeleteMapping("/tenants/{id}")
    public ResponseEntity<?> deleteAccount(
            @PathVariable("id") String id,
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = Headers.CLIENT_ID, required = false) String clientId) {
        ControllerSupport.failIfValidation(TenantValidator.validateTenantID(id));
        ControllerSupport.failIfValidation(TenantValidator.validateOptionalTenantIdHeader(tenantId));
        service.deleteById(id, clientId, tenantId);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("deleted", true);
        return ResponseEntity.ok(ok);
    }

    // ---------- Tenants (self-service signup) ----------

    @PostMapping("/tenants/registrations")
    public ResponseEntity<?> createSignup(
            @RequestHeader(value = Headers.CLIENT_ID, required = false) String clientId,
            @RequestHeader(value = Headers.REQUEST_ID, required = false) String requestId,
            jakarta.servlet.http.HttpServletRequest httpReq,
            @RequestBody(required = false) byte[] body) {
        SignupInitiateRequest req = ControllerSupport.parseBody(objectMapper, body, SignupInitiateRequest.class);

        TenantCreateRequest createReq = Mappers.signupToCreateRequest(req);
        ControllerSupport.failIfValidation(TenantValidator.validateTenantCreateRequest(createReq));

        // Fast-path duplicate-code check BEFORE issuing an OTP.
        String code = CodeGen.generateCodeFromName(req.getName());
        // Reject a name that derives an unusable code here too, so the caller is not sent an OTP
        // for a signup that can only fail at create.
        ControllerSupport.failIfValidation(TenantValidator.validateDerivedCode(code, req.getName()));
        TenantEntity existing = service.getRepo().getByCode(code);
        if (existing != null) {
            throw new CustomException("DUPLICATE_RECORD", "Tenant with this code already exists",
                    HttpStatus.CONFLICT);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("ip", httpReq.getRemoteAddr());
        OtpClient.GenerateResponse otpResp;
        try {
            otpResp = otpClient.generate(req.getEmail(), "email", Constants.OTP_PURPOSE_REGISTRATION, metadata);
        } catch (CustomException e) {
            if (OtpClient.CODE_RATE_LIMITED.equals(e.getCode())) {
                throw new CustomException("TOO_MANY_REQUESTS", "Rate limit exceeded: " + e.getMessage(),
                        HttpStatus.TOO_MANY_REQUESTS);
            }
            throw new CustomException("OTP_SERVICE_ERROR", "Failed to generate OTP: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException e) {
            throw otpUnavailable("Failed to generate OTP", e);
        }

        long ttl = otpResp.expiresIn;
        try {
            signupCache.store(otpResp.referenceId, createReq, ttl);
        } catch (RuntimeException e) {
            throw cacheFailure("failed to store the registration request", e);
        }

        SignupInitiateResponse resp = new SignupInitiateResponse();
        resp.setReferenceId(otpResp.referenceId);
        resp.setExpiresIn(otpResp.expiresIn);
        resp.setCooldownSeconds(otpResp.cooldownSeconds);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/tenants/registrations/verify")
    public ResponseEntity<?> verifySignup(
            @RequestHeader(value = Headers.CLIENT_ID, required = false) String clientId,
            @RequestHeader(value = Headers.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) byte[] body) {
        SignupVerifyRequest req = ControllerSupport.parseBody(objectMapper, body, SignupVerifyRequest.class);
        ControllerSupport.failIfValidation(SignupValidator.validateSignupVerifyRequest(req));

        TenantCreateRequest payload;
        try {
            payload = signupCache.get(req.getReferenceId());
        } catch (RuntimeException e) {
            throw cacheFailure("failed to read the registration request", e);
        }
        if (payload == null) {
            throw new CustomException("REQUEST_EXPIRED", "OTP request expired or not found",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        OtpClient.VerifyResponse otpResp;
        try {
            otpResp = otpClient.verify(req.getReferenceId(), req.getOtp(), req.getPurpose());
        } catch (CustomException e) {
            String c = e.getCode();
            if (OtpClient.CODE_LOCKED.equals(c)) {
                throw new CustomException("ACCOUNT_LOCKED", "Too many failed OTP attempts: " + e.getMessage(),
                        HttpStatus.LOCKED);
            }
            if (OtpClient.CODE_EXPIRED.equals(c)) {
                throw new CustomException("OTP_EXPIRED", "OTP has expired; restart registration: " + e.getMessage(),
                        HttpStatus.GONE);
            }
            if (OtpClient.CODE_INVALID_VALUE.equals(c)) {
                throw new CustomException("INVALID_OTP", "OTP value is incorrect: " + e.getMessage(),
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            throw new CustomException("OTP_SERVICE_ERROR", "OTP verification failed: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException e) {
            throw otpUnavailable("OTP verification failed", e);
        }
        if (!otpResp.verified) {
            throw new CustomException("INVALID_OTP", "OTP value is incorrect", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        TenantResponse resp = service.create(payload, clientId, requestId);

        try {
            signupCache.delete(req.getReferenceId());
        } catch (RuntimeException e) {
            // best-effort (Go warns)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PostMapping("/tenants/registrations/resend")
    public ResponseEntity<?> resendSignupOtp(
            jakarta.servlet.http.HttpServletRequest httpReq,
            @RequestBody(required = false) byte[] body) {
        SignupResendRequest req = ControllerSupport.parseBody(objectMapper, body, SignupResendRequest.class);
        ControllerSupport.failIfValidation(SignupValidator.validateSignupResendRequest(req));

        TenantCreateRequest pending;
        try {
            pending = signupCache.get(req.getReferenceId());
        } catch (RuntimeException e) {
            throw cacheFailure("failed to read the registration request", e);
        }
        if (pending == null) {
            throw new CustomException("REQUEST_NOT_FOUND",
                    "Request ID not found or expired: The signup request has expired or does not exist",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("ip", httpReq.getRemoteAddr());
        OtpClient.ResendResponse otpResp;
        try {
            otpResp = otpClient.resend(req.getReferenceId(), metadata);
        } catch (CustomException e) {
            String c = e.getCode();
            if (OtpClient.CODE_LOCKED.equals(c)) {
                throw new CustomException("ACCOUNT_LOCKED", "Identifier is in lockout period: " + e.getMessage(),
                        HttpStatus.LOCKED);
            }
            if (OtpClient.CODE_RATE_LIMITED.equals(c)) {
                throw new CustomException("TOO_MANY_REQUESTS",
                        "Resend too soon or max resend exceeded: " + e.getMessage(),
                        HttpStatus.TOO_MANY_REQUESTS);
            }
            throw new CustomException("OTP_SERVICE_ERROR", "Failed to resend OTP: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException e) {
            throw otpUnavailable("Failed to resend OTP", e);
        }

        SignupResendResponse resp = new SignupResendResponse();
        resp.setReferenceId(otpResp.referenceId);
        resp.setExpiresIn(otpResp.expiresIn);
        resp.setCooldownSeconds(otpResp.cooldownSeconds);
        return ResponseEntity.ok(resp);
    }

    /** The OTP service could not be reached or answered unreadably: logged, and a 503 like its other failures. */
    private static CustomException otpUnavailable(String message, RuntimeException cause) {
        log.error("{}: OTP service call failed", message, cause);
        return new CustomException("OTP_SERVICE_ERROR", message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** The registration store (Redis) failed: the service's own fault, so a 500. */
    private static CustomException cacheFailure(String message, RuntimeException cause) {
        log.error("{}", message, cause);
        return new CustomException("CACHE_ERROR", message, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
