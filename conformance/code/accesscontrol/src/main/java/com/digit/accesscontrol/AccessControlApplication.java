package com.digit.accesscontrol;

import com.digit.accesscontrol.config.AccessControlProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * DIGIT AccessControl service — Java/Spring Boot port of the Go accesscontrol service.
 * Behavior-preserving migration: same APIs, validation, persistence, tenant and pub/sub behavior.
 */
@SpringBootApplication
@EnableConfigurationProperties(AccessControlProperties.class)
public class AccessControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccessControlApplication.class, args);
    }
}
