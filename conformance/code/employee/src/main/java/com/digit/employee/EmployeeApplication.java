package com.digit.employee;

import com.digit.employee.config.EmployeeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * DIGIT Employee service — Java/Spring Boot port of the Go employee service.
 * Behavior-preserving migration: same APIs, validation, persistence, tenant and pub/sub behavior.
 */
@SpringBootApplication
@EnableConfigurationProperties(EmployeeProperties.class)
public class EmployeeApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmployeeApplication.class, args);
    }
}
