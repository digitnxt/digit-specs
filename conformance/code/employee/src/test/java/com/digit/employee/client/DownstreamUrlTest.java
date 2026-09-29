package com.digit.employee.client;

import com.digit.employee.config.EmployeeProperties;
import org.digit.tracer.config.TracerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Internal service clients concatenate host + path with no normalisation, so the convention — host
 * ends with a slash, path does not begin with one — is what keeps the URL well formed. These tests
 * drive the clients against a local server and assert the request-target that actually arrives.
 */
class DownstreamUrlTest {

    private static final DownstreamHttp HTTP = new DownstreamHttp(new TracerProperties());

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> received = new AtomicReference<>();
    private int status = 200;
    private String body = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        // Trailing slash: the convention every internal host default follows.
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.set(exchange.getRequestURI().toString());
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private EmployeeProperties props() {
        EmployeeProperties props = new EmployeeProperties();
        props.getIdgen().setHost(baseUrl);
        props.getBoundary().setBaseUrl(baseUrl);
        props.getIndividual().setHost(baseUrl);
        return props;
    }

    @Test
    void idgenUrl_usesSingleSlashBetweenHostAndPath() {
        body = "{\"id\":\"EMP-1\"}";
        EmployeeProperties props = props();
        props.getIdgen().setEnabled(true);

        List<String> ids = new IdGenClient(props, new ObjectMapper(), HTTP)
                .generateIDs("pg", 1, Map.of());

        assertEquals(List.of("EMP-1"), ids);
        assertEquals("/idgen/v3/generate", received.get());
    }

    @Test
    void individualUrl_appendsIdWithoutDoubleSlash() {
        status = 404; // 404 -> null, so no response body needs to be parsed
        String result = new IndividualClient(props(), new ObjectMapper(), HTTP)
                .getIndividualByID("pg", "IND-42");

        assertEquals(null, result);
        assertEquals("/individuals/v3/individuals/IND-42", received.get());
    }

    @Test
    void boundaryUrl_keepsQueryStringAfterPath() {
        body = "{\"tenantBoundary\":[]}";
        Set<String> found = new BoundaryClient(props(), new ObjectMapper(), HTTP)
                .searchRelationship("pg", "ADMIN", "CITY", List.of("C1"));

        assertTrue(found.isEmpty());
        assertTrue(received.get().startsWith("/boundary/v3/relationship?"),
                "unexpected request target: " + received.get());
    }

    /**
     * Endpoint paths are config-driven, not baked into the clients: re-pointing them (as a deployment
     * would, to re-route or version-bump an endpoint) must change the URL actually requested.
     */
    @Test
    void endpointPaths_areTakenFromConfig() {
        EmployeeProperties props = props();
        props.getIdgen().setPath("idgen/v4/generate");
        props.getIndividual().setPath("individuals/v4/individuals");
        props.getBoundary().setPath("boundary/v4/relationship");

        body = "{\"id\":\"EMP-1\"}";
        new IdGenClient(props, new ObjectMapper(), HTTP).generateIDs("pg", 1, Map.of());
        assertEquals("/idgen/v4/generate", received.get());

        status = 404;
        new IndividualClient(props, new ObjectMapper(), HTTP).getIndividualByID("pg", "IND-42");
        assertEquals("/individuals/v4/individuals/IND-42", received.get());

        status = 200;
        body = "{\"tenantBoundary\":[]}";
        new BoundaryClient(props, new ObjectMapper(), HTTP).searchRelationship("pg", "ADMIN", "CITY", List.of("C1"));
        assertTrue(received.get().startsWith("/boundary/v4/relationship?"),
                "unexpected request target: " + received.get());
    }

    /**
     * The clients only produce correct URLs if the shipped defaults obey the convention, so guard the
     * defaults themselves against drift.
     */
    @Test
    void shippedDefaults_hostEndsWithSlash_pathDoesNotStartWithOne() {
        EmployeeProperties defaults = new EmployeeProperties();

        for (String host : List.of(defaults.getIdgen().getHost(),
                defaults.getBoundary().getBaseUrl(),
                defaults.getIndividual().getHost())) {
            assertTrue(host.endsWith("/"), "host must end with a slash: " + host);
        }
        for (String path : List.of(defaults.getIdgen().getPath(),
                defaults.getBoundary().getPath(),
                defaults.getIndividual().getPath())) {
            assertFalse(path.startsWith("/"), "path must not start with a slash: " + path);
        }
    }
}
