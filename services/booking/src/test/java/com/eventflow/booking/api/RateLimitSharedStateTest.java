package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.jdbc.Sql;

import com.eventflow.booking.BookingApplication;

/**
 * Phase 5 gate: two independent Booking application instances share the same rate-limit window
 * because the counter lives in Redis. The second instance is a real application context started
 * by this test against the same containers, not a second limiter object.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "booking.rate-limit.join-max=2",
                "booking.rate-limit.window=PT1M",
                "booking.waiting-room.dispatch-interval=PT1H",
                "booking.expiry.interval=PT1H",
                "eventflow.messaging.outbox.interval=PT1H",
                "spring.rabbitmq.connection-timeout=2s"
        })
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RateLimitSharedStateTest extends BookingIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static ConfigurableApplicationContext second;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private StringRedisTemplate redis;

    @AfterAll
    static void stopSecondInstance() {
        if (second != null) {
            second.close();
        }
    }

    @BeforeEach
    void clearRateLimitCounters() {
        Set<String> keys = redis.keys("rate:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void twoBookingInstancesShareTheSameWindow() throws Exception {
        assertThat(second).isNull();
        second = startSecondInstance();
        int secondPort = ((WebServerApplicationContext) second).getWebServer().getPort();
        int firstPort = ((WebServerApplicationContext) context).getWebServer().getPort();

        UUID eventId = UUID.randomUUID();
        String path = "/api/waiting-room/" + eventId + "/join";
        String first = "http://localhost:" + firstPort + path;
        String other = "http://localhost:" + secondPort + path;

        assertThat(join(first, "shared-user")).isEqualTo(200);
        assertThat(join(other, "shared-user")).isEqualTo(200);

        HttpResponse<String> limited = post(other, "shared-user");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.body()).contains("RATE_LIMITED");

        // The key includes the subject, so another user still has a free window on the same replica.
        assertThat(join(other, "another-user")).isEqualTo(200);
        assertThat(join(first, "another-user")).isEqualTo(200);
    }

    private static ConfigurableApplicationContext startSecondInstance() {
        // Command-line arguments have higher precedence than application.yml, which is required:
        // defaults set through SpringApplicationBuilder.properties() would lose against the YAML.
        String[] arguments = {
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.rabbitmq.host=" + RABBITMQ.getHost(),
                "--spring.rabbitmq.port=" + RABBITMQ.getAmqpPort(),
                "--spring.rabbitmq.username=" + RABBITMQ.getAdminUsername(),
                "--spring.rabbitmq.password=" + RABBITMQ.getAdminPassword(),
                "--spring.rabbitmq.connection-timeout=2s",
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--booking.rate-limit.join-max=2",
                "--booking.rate-limit.window=PT1M",
                "--booking.waiting-room.dispatch-interval=PT1H",
                "--booking.expiry.interval=PT1H",
                "--eventflow.messaging.outbox.interval=PT1H"
        };
        return new SpringApplicationBuilder(BookingApplication.class).run(arguments);
    }

    private static int join(String url, String user) throws Exception {
        return post(url, user).statusCode();
    }

    private static HttpResponse<String> post(String url, String user) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("X-User-Id", user)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
