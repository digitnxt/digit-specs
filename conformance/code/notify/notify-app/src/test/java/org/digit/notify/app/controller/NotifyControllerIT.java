package org.digit.notify.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.digit.notify.app.constants.ErrorCodes;
import org.digit.tracer.model.CustomException;
import org.digit.notify.app.model.ChannelDispatchStatus;
import org.digit.notify.app.model.NotifyResponse;
import org.digit.notify.app.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "notify.plugins.directory=./providers",
    "spring.flyway.enabled=true"
})
@Testcontainers
class NotifyControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("notify").withUsername("notify").withPassword("notify");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired WebApplicationContext wac;
    @Autowired ResponseHeaderFilter responseHeaderFilter;
    final ObjectMapper objectMapper = new ObjectMapper();
    @MockitoBean NotificationService notificationService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .addFilters(responseHeaderFilter)
            .build();
    }

    @Test
    void postNotify_validRequest_returns202WithNotificationId() throws Exception {
        var response = new NotifyResponse("ntf_01ABC123", "OTP_SMS", List.of(
            new ChannelDispatchStatus("SMS", "DISPATCHED", "twilio", null)
        ));
        when(notificationService.sendNotification(any(), eq("tenant-1"))).thenReturn(response);

        var body = Map.of(
            "templateCode", "OTP_SMS",
            "recipient", Map.of("phone", "+911234567890", "deviceTokens", List.of()),
            "payload", Map.of("data", Map.of("otp", "123456"))
        );

        mockMvc.perform(post("/v3/notifications")
                .header("X-Tenant-ID", "tenant-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.notificationId").value("ntf_01ABC123"))
            .andExpect(header().exists("X-Response-Time"));
    }

    @Test
    void postNotify_missingTenantIdHeader_returns400() throws Exception {
        var body = Map.of(
            "templateCode", "OTP_SMS",
            "recipient", Map.of("phone", "+911234567890", "deviceTokens", List.of()),
            "payload", Map.of()
        );

        mockMvc.perform(post("/v3/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    /**
     * Goes through MVC and the tracer's ExceptionAdvise, so it pins the wire format as well as the
     * status: a bare array, which is what every other DIGIT service answers with and what this
     * service's own handler used to break by returning a single object.
     */
    @Test
    void postNotify_configNotFound_returns404AsAPlatformErrorArray() throws Exception {
        when(notificationService.sendNotification(any(), any()))
            .thenThrow(new CustomException(ErrorCodes.NOT_FOUND,
                "NotificationConfig not found with id: OTP_SMS", HttpStatus.NOT_FOUND));

        var body = Map.of(
            "templateCode", "OTP_SMS",
            "recipient", Map.of("phone", "+911234567890", "deviceTokens", List.of()),
            "payload", Map.of()
        );

        mockMvc.perform(post("/v3/notifications")
                .header("X-Tenant-ID", "tenant-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].code").value("NOT_FOUND"))
            .andExpect(jsonPath("$[0].message").value("NotificationConfig not found with id: OTP_SMS"));
    }
}
