package com.digit.account.config;

import com.digit.account.cache.SignupCache;
import com.digit.account.clients.keycloak.KeycloakClient;
import com.digit.account.clients.notification.NotificationClient;
import com.digit.account.clients.otp.OtpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the outbound clients (Keycloak, OTP, Notification) + the signup cache. */
@Configuration
public class ClientsConfig {

    @Bean
    public NotificationClient notificationClient(AccountProperties props, ObjectMapper objectMapper) {
        return new NotificationClient(props.getNotification(), objectMapper);
    }

    @Bean
    public KeycloakClient keycloakClient(AccountProperties props, ObjectMapper objectMapper) {
        AccountProperties.Keycloak kc = props.getKeycloak();
        return new KeycloakClient(kc.getBaseUrl(), kc.getAdminUser(), kc.getAdminPass(), kc,
                props.getClient().getRedirectUrl(), objectMapper);
    }

    @Bean
    public OtpClient otpClient(AccountProperties props, ObjectMapper objectMapper) {
        return new OtpClient(props.getOtp(), objectMapper);
    }

    @Bean(destroyMethod = "close")
    public SignupCache signupCache(AccountProperties props, ObjectMapper objectMapper) {
        AccountProperties.Redis r = props.getPubsub().getRedis();
        SignupCache cache = new SignupCache(r.getAddress(), r.getPassword(), r.getDb(), objectMapper);
        // Mirror Go: main.go eagerly builds the Redis client (NewRedisClient PINGs within 5s) and
        // log.Fatals on failure. Connect here so an unreachable Redis aborts startup the same way.
        cache.connect();
        return cache;
    }
}
