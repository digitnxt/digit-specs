package com.digit.individual.web;

import com.digit.individual.constants.ErrorCodes;
import com.digit.individual.constants.Headers;
import com.digit.individual.constants.ValidationConstants;
import com.digit.individual.model.ExistsResponse;
import com.digit.individual.model.Individual;
import com.digit.individual.model.IndividualDTO;
import com.digit.individual.model.IndividualSearchResponse;
import com.digit.individual.model.ModelMappers;
import com.digit.individual.model.RequestContext;
import com.digit.individual.model.SearchCriteria;
import com.digit.individual.repository.IndividualRepository;
import com.digit.individual.service.IndividualService;
import com.digit.individual.service.RequestValidator;
import jakarta.servlet.http.HttpServletRequest;
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
import org.digit.tracer.model.CustomException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Individuals REST resource. Mirrors Go internal/handlers/individual_handler.go + routes.
 * Mounted under {@code <context-path>/v3/individuals} (default /individuals/v3/individuals).
 */
@RestController
@RequestMapping("${individual.server.context-path:/individuals}/v3/individuals")
public class IndividualController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final IndividualService service;
    private final RequestValidator validator;
    private final JsonMapper strictMapper;

    public IndividualController(IndividualService service, RequestValidator validator,
                                @org.springframework.beans.factory.annotation.Qualifier("strictJsonMapper")
                                JsonMapper strictMapper) {
        this.service = service;
        this.validator = validator;
        this.strictMapper = strictMapper;
    }

    private RequestContext ctx(HttpServletRequest req, String tenantId, String userId) {
        Object rid = req.getAttribute(ForwardHeadersFilter.REQUEST_ID_ATTR);
        return new RequestContext(tenantId, userId, rid == null ? null : rid.toString());
    }

    @PostMapping
    public ResponseEntity<IndividualDTO> create(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = Headers.USER_ID, required = false) String userId,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        IndividualDTO dto = parseBody(body);
        Individual ind = ModelMappers.toEntity(dto);
        ind.setTenantId(tenantId);

        validator.validateCreate(ind);
        RequestContext rc = ctx(request, tenantId, userId);
        Individual created = service.createIndividual(ind, rc);

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/individuals/v3/individuals/" + created.getId())
                .body(ModelMappers.toDto(created));
    }

    @GetMapping
    public ResponseEntity<IndividualSearchResponse> search(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestParam(value = "id", required = false) List<String> id,
            @RequestParam(value = "individualId", required = false) List<String> individualId,
            @RequestParam(value = "givenName", required = false) String givenName,
            @RequestParam(value = "mobileNumber", required = false) String mobileNumber,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "dateOfBirth", required = false) String dateOfBirth,
            @RequestParam(value = "includeDeleted", required = false, defaultValue = "false") String includeDeletedRaw,
            @RequestParam(value = "page", required = false) String pageRaw,
            @RequestParam(value = "size", required = false) String sizeRaw) {

        // Binding validation (mirrors go-playground tags on IndividualSearchFilter). page/size/
        // includeDeleted are taken as String and parsed here so a non-numeric/non-boolean value
        // becomes a 400 validation error rather than a Spring type-mismatch (which tracer reports as 500).
        Map<String, String> errs = new LinkedHashMap<>();
        boolean includeDeleted = parseBoolParam(errs, "includeDeleted", includeDeletedRaw);
        Integer page = parseIntParam(errs, "page", pageRaw);
        Integer size = parseIntParam(errs, "size", sizeRaw);
        if (id != null) {
            for (String s : id) {
                if (!isUuid(s)) {
                    bindingError(errs, "id", "uuid");
                }
            }
        }
        if (individualId != null) {
            for (String s : individualId) {
                if (s.length() > 64) {
                    bindingError(errs, "individualId", "max");
                }
            }
        }
        if (givenName != null && (givenName.isEmpty() || givenName.length() > 128)) {
            bindingError(errs, "givenName", givenName.isEmpty() ? "min" : "max");
        }
        if (mobileNumber != null && mobileNumber.length() > 20) {
            bindingError(errs, "mobileNumber", "max");
        }
        if (gender != null && !isValidGender(gender)) {
            bindingError(errs, "gender", "oneof");
        }
        if (dateOfBirth != null && !isIsoDate(dateOfBirth)) {
            bindingError(errs, "dateOfBirth", "datetime");
        }
        if (page != null && page < 1) {
            bindingError(errs, "page", "min");
        }
        if (size != null && (size < 1 || size > 100)) {
            bindingError(errs, "size", size < 1 ? "min" : "max");
        }
        if (!errs.isEmpty()) {
            throw new CustomException(errs);
        }

        int p = page == null ? DEFAULT_PAGE : page;
        int sz = size == null ? DEFAULT_PAGE_SIZE : size;

        SearchCriteria criteria = new SearchCriteria();
        criteria.setGivenName(givenName);
        criteria.setGender(gender);
        criteria.setDateOfBirth(dateOfBirth);
        if (id != null && !id.isEmpty()) {
            criteria.setId(id);
        }
        if (individualId != null && !individualId.isEmpty()) {
            criteria.setIndividualId(individualId);
        }
        if (mobileNumber != null && !mobileNumber.isEmpty()) {
            criteria.setMobileNumber(List.of(mobileNumber));
        }

        IndividualRepository.SearchResult result =
                service.searchIndividuals(criteria, p, sz, includeDeleted, tenantId);
        boolean hasMore = (long) p * (long) sz < result.totalCount();
        IndividualSearchResponse resp = new IndividualSearchResponse(result.totalCount(), p, sz, hasMore,
                ModelMappers.toDtoList(result.individuals()));
        return ResponseEntity.ok(resp);
    }

    @GetMapping(value = "/exists")
    public ResponseEntity<ExistsResponse> exists(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "individualId", required = false) String individualId,
            @RequestParam(value = "givenName", required = false) String givenName,
            @RequestParam(value = "mobileNumber", required = false) String mobileNumber,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "dateOfBirth", required = false) String dateOfBirth,
            @RequestParam(value = "includeDeleted", required = false, defaultValue = "false") String includeDeletedRaw) {

        Map<String, String> errs = new LinkedHashMap<>();
        boolean includeDeleted = parseBoolParam(errs, "includeDeleted", includeDeletedRaw);
        if (id != null && !id.isEmpty() && !isUuid(id)) {
            bindingError(errs, "id", "uuid");
        }
        if (individualId != null && individualId.length() > 64) {
            bindingError(errs, "individualId", "max");
        }
        if (givenName != null && (givenName.isEmpty() || givenName.length() > 128)) {
            bindingError(errs, "givenName", givenName.isEmpty() ? "min" : "max");
        }
        if (mobileNumber != null && mobileNumber.length() > 20) {
            bindingError(errs, "mobileNumber", "max");
        }
        if (gender != null && !isValidGender(gender)) {
            bindingError(errs, "gender", "oneof");
        }
        if (dateOfBirth != null && !isIsoDate(dateOfBirth)) {
            bindingError(errs, "dateOfBirth", "datetime");
        }
        if (!errs.isEmpty()) {
            throw new CustomException(errs);
        }

        boolean hasFilter = nonEmpty(id) || nonEmpty(individualId) || nonEmpty(givenName)
                || nonEmpty(mobileNumber) || nonEmpty(gender) || nonEmpty(dateOfBirth);
        if (!hasFilter) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "At least one filter parameter is required");
        }

        SearchCriteria criteria = new SearchCriteria();
        criteria.setGivenName(givenName);
        criteria.setGender(gender);
        criteria.setDateOfBirth(dateOfBirth);
        if (nonEmpty(id)) {
            criteria.setId(List.of(id));
        }
        if (nonEmpty(individualId)) {
            criteria.setIndividualId(List.of(individualId));
        }
        if (nonEmpty(mobileNumber)) {
            criteria.setMobileNumber(List.of(mobileNumber));
        }

        boolean exists = service.individualExists(criteria, tenantId, includeDeleted);
        return ResponseEntity.ok(new ExistsResponse(exists));
    }

    @GetMapping(value = "/{id}")
    public ResponseEntity<IndividualDTO> get(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @PathVariable("id") String id) {
        requireValidId(id);
        SearchCriteria criteria = new SearchCriteria();
        criteria.setId(List.of(id));
        IndividualRepository.SearchResult result = service.searchIndividuals(criteria, 1, 1, false, tenantId);
        if (result.individuals().isEmpty()) {
            throw new CustomException(ErrorCodes.NON_EXISTENT_ENTITY, "Individual not found", HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(ModelMappers.toDto(result.individuals().get(0)));
    }

    @PutMapping(value = "/{id}")
    public ResponseEntity<IndividualDTO> update(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = Headers.USER_ID, required = false) String userId,
            @PathVariable("id") String id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        requireValidId(id);
        IndividualDTO dto = parseBody(body);
        Individual ind = ModelMappers.toEntity(dto);
        ind.setTenantId(tenantId);
        ind.setId(id);

        validator.validateUpdate(ind);
        RequestContext rc = ctx(request, tenantId, userId);
        Individual updated = service.updateIndividual(ind, rc);
        return ResponseEntity.ok(ModelMappers.toDto(updated));
    }

    @DeleteMapping(value = "/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = Headers.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = Headers.USER_ID, required = false) String userId,
            @PathVariable("id") String id,
            HttpServletRequest request) {
        requireValidId(id);
        Individual ind = new Individual();
        ind.setId(id);
        ind.setTenantId(tenantId);

        validator.validateDelete(ind);
        RequestContext rc = ctx(request, tenantId, userId);
        service.deleteIndividual(ind, rc);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------- helpers

    private IndividualDTO parseBody(byte[] body) {
        if (body == null || body.length == 0) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "Invalid request body: EOF");
        }
        try {
            return strictMapper.readValue(body, IndividualDTO.class);
        } catch (Exception e) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "Invalid request body: " + e.getMessage());
        }
    }

    private void requireValidId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "ID is required");
        }
        if (!isUuid(id)) {
            throw new CustomException(ErrorCodes.VALIDATION_ERROR, "ID must be a valid UUID");
        }
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean isUuid(String s) {
        if (s == null) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isValidGender(String g) {
        return g != null && ValidationConstants.VALID_GENDERS.contains(g);
    }

    private static boolean isIsoDate(String s) {
        try {
            java.time.LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Records a binding violation keyed by field path (the error code) with a go-playground-style message. */
    private static void bindingError(Map<String, String> errors, String fieldName, String tag) {
        errors.put(fieldName, "field '" + fieldName + "' failed '" + tag + "' validation");
    }

    /** Parses an optional integer query param; blank/absent → null, non-numeric → recorded 400 binding error. */
    private static Integer parseIntParam(Map<String, String> errors, String fieldName, String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            bindingError(errors, fieldName, "int");
            return null;
        }
    }

    /** Parses an optional boolean query param; blank/absent → false, unrecognised → recorded 400 binding error. */
    private static boolean parseBoolParam(Map<String, String> errors, String fieldName, String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        switch (raw) {
            case "true": case "1": case "t": case "T": case "TRUE": case "True":
                return true;
            case "false": case "0": case "f": case "F": case "FALSE": case "False":
                return false;
            default:
                bindingError(errors, fieldName, "bool");
                return false;
        }
    }
}
