package com.digit.individual;

import com.digit.individual.config.IndividualProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * DIGIT Individual service — Java/Spring Boot port of the Go individual service.
 * Behavior-preserving migration: same APIs, validation, persistence, tenant and pub/sub behavior.
 */
@SpringBootApplication
@EnableConfigurationProperties(IndividualProperties.class)
public class IndividualApplication {
    public static void main(String[] args) {
        SpringApplication.run(IndividualApplication.class, args);
    }
}
