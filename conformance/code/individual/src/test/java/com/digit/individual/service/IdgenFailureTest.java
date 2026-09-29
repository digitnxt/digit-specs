package com.digit.individual.service;

import com.digit.individual.client.IdgenClient;
import com.digit.individual.config.IndividualProperties;
import com.digit.individual.model.Individual;
import com.digit.individual.model.RequestContext;
import com.sun.net.httpserver.HttpServer;
import org.digit.tracer.config.TracerProperties;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a caller sees when IDGen fails: IDGen's own error when it answered, "unavailable" when it
 * could not be reached in time — never its raw body or transport detail. Always 502.
 */
class IdgenFailureTest {

    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private volatile long delayMs;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CustomException failCreate(String host) {
        IndividualProperties props = new IndividualProperties();
        props.getIdgen().setEnabled(true);
        props.getIdgen().setHost(host);
        TracerProperties tracer = new TracerProperties();
        tracer.getHttp().setConnectTimeoutMs(500);
        tracer.getHttp().setReadTimeoutMs(300);
        EnrichmentService enrichment = new EnrichmentService(new IdgenClient(props, tracer), props);
        Individual ind = new Individual();
        ind.setTenantId("pg");
        return assertThrows(CustomException.class,
                () -> enrichment.enrichForCreate(ind, new RequestContext("pg", "u1", "r1")));
    }

    private String serverHost() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    void idgenReportedError_isPassedOnAsItsCodeAndMessage() {
        status = 404;
        body = "[{\"code\":\"NOT_FOUND\",\"message\":\"template not found\"}]";

        CustomException ex = failCreate(serverHost());

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("DOWNSTREAM_ERROR", ex.getCode());
        assertEquals("idgen: failed to generate individualId: NOT_FOUND template not found", ex.getMessage());
    }

    @Test
    void unstructuredErrorBody_isNotEchoed() {
        status = 500;
        body = "<html>internal proxy error at 10.0.3.17</html>";

        CustomException ex = failCreate(serverHost());

        assertEquals("idgen: failed to generate individualId", ex.getMessage());
    }

    @Test
    void unreachableIdgen_isReportedAsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        CustomException ex = failCreate("http://127.0.0.1:" + closedPort + "/");

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("idgen unavailable: failed to generate individualId", ex.getMessage());
    }

    @Test
    void slowIdgen_timesOutAtTheSharedReadTimeout() {
        status = 200;
        body = "{\"id\":\"IND-1\"}";
        delayMs = 1_500;

        long start = System.nanoTime();
        CustomException ex = failCreate(serverHost());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("idgen unavailable: failed to generate individualId", ex.getMessage());
        assertTrue(elapsedMs < 1_200, "should fail at the 300 ms read timeout, took " + elapsedMs + " ms");
    }
}
