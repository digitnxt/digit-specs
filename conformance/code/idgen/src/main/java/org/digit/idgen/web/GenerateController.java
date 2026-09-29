package org.digit.idgen.web;

import jakarta.validation.Valid;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.service.GenerationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v3/generate")
public class GenerateController {

    private final GenerationService service;

    public GenerateController(GenerationService service) {
        this.service = service;
    }

    @PostMapping
    public Dtos.GenerateResponse generate(
            @RequestHeader(HeaderInterceptor.TENANT_ID) String tenantId,
            @Valid @RequestBody Dtos.GenerateRequest request) {
        return service.generate(tenantId, request);
    }

    @PostMapping("/bulk")
    public Dtos.BulkGenerateResponse bulkGenerate(
            @RequestHeader(HeaderInterceptor.TENANT_ID) String tenantId,
            @Valid @RequestBody Dtos.BulkGenerateRequest request) {
        return service.bulkGenerate(tenantId, request);
    }
}
