package com.digit.accesscontrol.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health endpoint. Mirrors Go GET /health (router root, outside the API group):
 * returns {"status":"healthy","service":"accesscontrol"}.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "healthy");
        body.put("service", "accesscontrol");
        return ResponseEntity.ok(body);
    }
}
