package com.digit.employee.client;

import org.digit.tracer.config.TracerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The one JDK {@link HttpClient} every downstream client sends through, bounded by the platform's
 * shared {@code tracer.http.connectTimeoutMs} / {@code tracer.http.readTimeoutMs}. The JDK defaults
 * are unbounded, so a stuck dependency would otherwise pin the request thread indefinitely.
 */
@Component
public class DownstreamHttp {

    private final HttpClient client;
    private final Duration requestTimeout;

    public DownstreamHttp(TracerProperties tracer) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(tracer.getHttp().getConnectTimeoutMs()))
                .build();
        this.requestTimeout = Duration.ofMillis(tracer.getHttp().getReadTimeoutMs());
    }

    /** Sends with the request timeout applied; exceeding it throws {@link java.net.http.HttpTimeoutException}. */
    public HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.timeout(requestTimeout).build(), HttpResponse.BodyHandlers.ofString());
    }
}