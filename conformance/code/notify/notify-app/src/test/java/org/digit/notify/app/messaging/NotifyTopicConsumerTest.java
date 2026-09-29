package org.digit.notify.app.messaging;

import com.digit.tenant.migration.service.MigrationService;
import com.digit.tenant.migration.web.TenantContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.digit.notify.app.controller.mapper.NotifyMapperImpl;
import org.digit.notify.app.model.NotifyRequest;
import org.digit.notify.app.model.NotifyResponse;
import org.digit.notify.app.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consumer has to reject what the API rejects. Over HTTP, {@code @Valid} on the request body is
 * applied by Spring MVC; there is no MVC here, so the constraints are checked explicitly and these
 * tests are what confirm the two entry points agree.
 *
 * <p>The other thing MVC supplies over HTTP is the tenant binding, from TenantContextFilter. A
 * consumer thread has neither, so the two schema modes are covered here for the same reason.
 */
class NotifyTopicConsumerTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final NotificationService service = mock(NotificationService.class);
    private final MigrationService migration = mock(MigrationService.class);
    private final NotifyTopicConsumer consumer = new NotifyTopicConsumer(
            null, service, migration, new NotifyMapperImpl(), new ObjectMapper(), VALIDATOR,
            true, "notify-send", "notify-service");

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void dispatchesThroughTheServiceWithTheTenantFromTheMessage() {
        when(service.sendNotification(any(), eq("default")))
                .thenReturn(new NotifyResponse("ntf_1", "otp-login", List.of()));

        consumer.handleMessage(json("""
                {"tenantId":"default","request":{"templateCode":"otp-login",
                 "recipient":{"email":"someone@example.com"},"payload":{"otp":"123456"}}}"""));

        ArgumentCaptor<NotifyRequest> captor = ArgumentCaptor.forClass(NotifyRequest.class);
        verify(service).sendNotification(captor.capture(), eq("default"));
        assertThat(captor.getValue().templateCode()).isEqualTo("otp-login");
        assertThat(captor.getValue().recipient().email()).isEqualTo("someone@example.com");
        // toDomain replaces the absent collections with empty ones, so nothing downstream sees null.
        assertThat(captor.getValue().metadata()).isEmpty();
        assertThat(captor.getValue().recipient().deviceTokens()).isEmpty();
    }

    /** The constraint being checked lives on the nested request, so this is the cascade working. */
    @Test
    void rejectsABlankTemplateCodeInsideTheRequest() {
        assertThatThrownBy(() -> consumer.handleMessage(json("""
                {"tenantId":"default","request":{"templateCode":"",
                 "recipient":{"email":"someone@example.com"},"payload":{"otp":"1"}}}""")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("templateCode");
        verify(service, never()).sendNotification(any(), any());
    }

    @Test
    void rejectsAMissingPayloadInsideTheRequest() {
        assertThatThrownBy(() -> consumer.handleMessage(json("""
                {"tenantId":"default","request":{"templateCode":"otp-login",
                 "recipient":{"email":"someone@example.com"}}}""")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
        verify(service, never()).sendNotification(any(), any());
    }

    @Test
    void rejectsAMissingTenant() {
        assertThatThrownBy(() -> consumer.handleMessage(json("""
                {"request":{"templateCode":"otp-login",
                 "recipient":{"email":"someone@example.com"},"payload":{"otp":"1"}}}""")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        verify(service, never()).sendNotification(any(), any());
    }

    /** Throwing rather than swallowing is what routes a poison record to the tracer's dead letter. */
    @Test
    void rejectsAnUnreadablePayload() {
        assertThatThrownBy(() -> consumer.handleMessage(json("not json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreadable");
        verify(service, never()).sendNotification(any(), any());
    }

    private static byte[] sendFor(String tenantId) {
        return json("""
                {"tenantId":"%s","request":{"templateCode":"otp-login",
                 "recipient":{"email":"someone@example.com"},"payload":{"otp":"1"}}}"""
                .formatted(tenantId));
    }

    /**
     * Without this the connection routes at the shared schema and the send looks up its config in
     * the wrong place, so the schema is asserted while the service call is in flight rather than
     * after it.
     */
    @Test
    void bindsTheTenantSchemaWhenMigrationIsEnabled() {
        when(migration.isEnabled()).thenReturn(true);
        List<String> boundDuringSend = new ArrayList<>();
        doAnswer(i -> {
            boundDuringSend.add(TenantContext.getSchema());
            return new NotifyResponse("ntf_1", "otp-login", List.of());
        }).when(service).sendNotification(any(), eq("pb"));

        consumer.handleMessage(sendFor("pb"));

        assertThat(boundDuringSend).containsExactly("pb");
        assertThat(TenantContext.isBound()).isFalse();   // scope closed again
    }

    /** With it off there is no such schema, so binding one would fail; the shared one is correct. */
    @Test
    void usesTheSharedSchemaWhenMigrationIsDisabled() {
        when(migration.isEnabled()).thenReturn(false);
        List<String> boundDuringSend = new ArrayList<>();
        doAnswer(i -> {
            boundDuringSend.add(TenantContext.getSchema());
            return new NotifyResponse("ntf_1", "otp-login", List.of());
        }).when(service).sendNotification(any(), eq("pb"));

        consumer.handleMessage(sendFor("pb"));

        assertThat(boundDuringSend).containsExactly("public");
    }

    /**
     * A message is no more trustworthy than a header. The id becomes a schema name, so one that is
     * not a legal schema name has to be refused before it reaches SET search_path — and refused by
     * throwing, so the record dead-letters rather than being dropped.
     */
    @Test
    void rejectsATenantThatIsNotAUsableSchemaName() {
        when(migration.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> consumer.handleMessage(sendFor("public")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(service, never()).sendNotification(any(), any());
    }
}