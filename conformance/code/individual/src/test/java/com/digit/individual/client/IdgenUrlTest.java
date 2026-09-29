package com.digit.individual.client;

import com.digit.individual.config.IndividualProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.digit.tracer.config.TracerProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The idgen client concatenates host + path with no normalisation, so the convention — host ends with
 * a slash, path does not begin with one — is what keeps the URL well formed. Drives the client
 * against a local server and asserts the request-target that actually arrives.
 */
class IdgenUrlTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> received = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        // Trailing slash: the convention the internal host default follows.
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.set(exchange.getRequestURI().toString());
        byte[] out = "{\"id\":\"IND-1\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Test
    void idgenUrl_usesSingleSlashBetweenHostAndPath() {
        IndividualProperties props = new IndividualProperties();
        props.getIdgen().setHost(baseUrl);
        props.getIdgen().setEnabled(true);

        List<String> ids = new IdgenClient(props, new TracerProperties()).generateIds("pg", "individual", 1, Map.of());

        assertEquals(List.of("IND-1"), ids);
        assertEquals("/idgen/v3/generate", received.get());
    }

    /**
     * The endpoint path is config-driven, not baked into the client: re-pointing it (as a deployment
     * would, to re-route or version-bump the endpoint) must change the URL actually requested.
     */
    @Test
    void endpointPath_isTakenFromConfig() {
        IndividualProperties props = new IndividualProperties();
        props.getIdgen().setHost(baseUrl);
        props.getIdgen().setEnabled(true);
        props.getIdgen().setPath("idgen/v4/generate");

        new IdgenClient(props, new TracerProperties()).generateIds("pg", "individual", 1, Map.of());

        assertEquals("/idgen/v4/generate", received.get());
    }

    /**
     * The client only produces a correct URL if the shipped defaults obey the convention, so guard the
     * defaults themselves against drift.
     */
    @Test
    void shippedDefaults_hostEndsWithSlash_pathDoesNotStartWithOne() {
        IndividualProperties.Idgen defaults = new IndividualProperties().getIdgen();

        assertTrue(defaults.getHost().endsWith("/"),
                "host must end with a slash: " + defaults.getHost());
        assertFalse(defaults.getPath().startsWith("/"),
                "path must not start with a slash: " + defaults.getPath());
    }
}
