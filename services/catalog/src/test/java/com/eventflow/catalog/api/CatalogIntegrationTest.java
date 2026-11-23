package com.eventflow.catalog.api;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers for every Catalog integration test, started once per JVM and never stopped: the
 * Spring context is cached across test classes, so a container that stopped between classes would
 * leave the cached datasource pointing at a dead port.
 *
 * <p>The AMQP port is pinned because the outage test stops and restarts the broker: Docker
 * reassigns a random host port when a container with a random binding restarts, while an explicit
 * binding survives. Only 5672 is exposed here; the management port is not used by the test.
 * Flyway is the schema source and each test starts from empty tables.
 */
@SpringBootTest(properties = {
        "eventflow.messaging.outbox.interval=200ms",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
abstract class CatalogIntegrationTest {

    private static final int AMQP_PORT = availablePort();

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));

    static {
        RABBITMQ.setExposedPorts(List.of(5672));
        RABBITMQ.setPortBindings(List.of(AMQP_PORT + ":5672"));
        POSTGRES.start();
        RABBITMQ.start();
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
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException unavailable) {
            throw new IllegalStateException("No free local port for the RabbitMQ binding", unavailable);
        }
    }
}
