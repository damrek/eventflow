package com.eventflow.payment.api;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Real PostgreSQL, RabbitMQ and HTTP provider for every Payment test. Containers and the provider
 * are started once per JVM and never stopped: the Spring context is cached across classes, so
 * stopping them between classes would break the cached datasource, connection factory and client.
 *
 * <p>The read timeout is shorter than the provider's "slow" scenario, so the timeout is produced
 * by the HTTP client and not by a sleeping test.
 */
@SpringBootTest(properties = {
        "eventflow.messaging.outbox.interval=200ms",
        "eventflow.messaging.retry.delays=200ms,200ms,200ms",
        "spring.rabbitmq.connection-timeout=2s",
        "spring.http.clients.connect-timeout=1s",
        "spring.http.clients.read-timeout=1s"
})
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
abstract class PaymentIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));
    static final FakePaymentProvider PROVIDER = new FakePaymentProvider();

    static {
        POSTGRES.start();
        RABBITMQ.start();
    }

    @Autowired
    private CircuitBreaker paymentProviderCircuitBreaker;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("payment.provider.url", PROVIDER::url);
    }

    @BeforeEach
    void resetProvider() {
        PROVIDER.reset();
        paymentProviderCircuitBreaker.reset();
    }
}
