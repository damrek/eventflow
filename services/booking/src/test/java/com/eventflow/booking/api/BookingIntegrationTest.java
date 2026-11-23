package com.eventflow.booking.api;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers shared by every Booking integration test, started once per JVM and never stopped: a
 * test context is cached across classes, so a container stopped between classes would break the
 * cached datasource and connection factories. Booking publishes and consumes events, so the
 * context needs a real broker and a real database; the waiting room and rate limit need Redis.
 *
 * <p>The Redis port is pinned because the outage test stops and restarts the container: Docker
 * reassigns a random host port on restart, while an explicit binding survives.
 */
abstract class BookingIntegrationTest {

    private static final int REDIS_PORT = availablePort();

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    static {
        REDIS.setPortBindings(List.of(REDIS_PORT + ":6379"));
        POSTGRES.start();
        RABBITMQ.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException unavailable) {
            throw new IllegalStateException("No free local port for the Redis binding", unavailable);
        }
    }
}
