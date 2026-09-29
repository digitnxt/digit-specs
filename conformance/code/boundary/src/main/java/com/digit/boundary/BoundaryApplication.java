package com.digit.boundary;

import com.digit.boundary.config.BoundaryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * DIGIT Boundary service — Java/Spring Boot port of the Go boundary service.
 * Behavior-preserving migration: same APIs, validation, persistence, tenant and pub/sub behavior.
 */
@SpringBootApplication
@EnableConfigurationProperties(BoundaryProperties.class)
public class BoundaryApplication {
    public static void main(String[] args) {
        SpringApplication.run(BoundaryApplication.class, args);
    }
}
