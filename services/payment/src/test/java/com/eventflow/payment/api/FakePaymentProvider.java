package com.eventflow.payment.api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A real HTTP double of the provider contract, scripted per test. It is not a mock object: the
 * gateway opens a socket, sends JSON and applies its own timeouts and circuit breaker, so the
 * timeout and outage paths are exercised end to end. The simulator app uses the same contract
 * with amount-based rules; this double only needs per-test control.
 */
final class FakePaymentProvider {

    enum Response {
        APPROVED,
        REJECTED,
        UNAVAILABLE,
        SLOW
    }

    private static final Duration SLOW_DELAY = Duration.ofSeconds(2);

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final BlockingDeque<Response> script = new LinkedBlockingDeque<>();
    private final AtomicInteger calls = new AtomicInteger();

    FakePaymentProvider() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException failed) {
            throw new IllegalStateException("Could not start the fake payment provider", failed);
        }
        server.createContext("/simulator/authorizations", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/simulator/authorizations";
    }

    /** Replaces the script: each request consumes one response, defaulting to an approval. */
    void script(Response... responses) {
        script.clear();
        Collections.addAll(script, responses);
    }

    void always(Response response) {
        script.clear();
        for (int i = 0; i < 100; i++) {
            script.add(response);
        }
    }

    int calls() {
        return calls.get();
    }

    void reset() {
        script.clear();
        calls.set(0);
    }

    private void handle(HttpExchange exchange) {
        try {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            Response response = script.poll();
            if (response == null) {
                response = Response.APPROVED;
            }
            switch (response) {
                case APPROVED -> write(exchange, 200, """
                        {"outcome":"APPROVED","providerReference":"SIM-TEST","reason":null}""");
                case REJECTED -> write(exchange, 200, """
                        {"outcome":"REJECTED","providerReference":"SIM-TEST","reason":"Simulated decline"}""");
                case UNAVAILABLE -> write(exchange, 503, """
                        {"code":"SIMULATED_PROVIDER_ERROR","detail":"Simulated outage"}""");
                case SLOW -> {
                    sleep(SLOW_DELAY);
                    write(exchange, 200, """
                            {"outcome":"APPROVED","providerReference":"SIM-TEST-SLOW","reason":null}""");
                }
            }
        } catch (IOException clientGone) {
            // The client timed out and closed the connection; the test already has its evidence.
        } finally {
            exchange.close();
        }
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
