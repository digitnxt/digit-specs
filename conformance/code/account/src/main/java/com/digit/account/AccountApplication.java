package com.digit.account;

import com.digit.account.config.AccountProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * DIGIT Account (tenant) service — Java/Spring Boot port of the Go account service.
 * Behavior-preserving migration: same APIs, validation, persistence, tenant and pub/sub behavior.
 */
@SpringBootApplication
@EnableConfigurationProperties(AccountProperties.class)
public class AccountApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountApplication.class, args);
    }
}
