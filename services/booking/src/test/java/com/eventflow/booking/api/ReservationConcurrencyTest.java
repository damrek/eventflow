package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Phase 2 quality gate: with 100 units and concurrent demand, PostgreSQL must never hold or
 * sell more than capacity. Requests are released together so they actually contend for the row.
 */
@SpringBootTest(properties = {
        "booking.expiry.interval=PT1H",
        "spring.datasource.hikari.maximum-pool-size=20"
})
@AutoConfigureMockMvc
@Testcontainers
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReservationConcurrencyTest {

    private static final int CAPACITY = 100;
    private static final int ATTEMPTS = 200;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();

    @Test
    void neverHoldsMoreUnitsThanCapacity() throws Exception {
        provision();

        List<Integer> statuses = runConcurrently(ATTEMPTS, index -> reservationStatus("attempt-" + index));

        assertThat(statuses).filteredOn(status -> status == 201).hasSize(CAPACITY);
        assertThat(statuses).filteredOn(status -> status == 422).hasSize(ATTEMPTS - CAPACITY);
        assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(201, 422));

        assertInventory(0, 100, 0);
        assertThat(count("select count(*) from reservation")).isEqualTo(CAPACITY);
        assertThat(count("select count(*) from reservation where status <> 'HELD'")).isZero();
    }

    @Test
    void sameIdempotencyKeyCreatesOneReservation() throws Exception {
        provision();

        List<Integer> statuses = runConcurrently(20, index -> reservationStatus("same-key"));

        assertThat(statuses).containsOnly(201);
        assertThat(count("select count(*) from reservation")).isEqualTo(1);
        assertInventory(99, 1, 0);
    }

    private int reservationStatus(String idempotencyKey) {
        try {
            MvcResult result = mockMvc.perform(post("/api/reservations")
                            .header("X-User-Id", ApiFixtures.USER)
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 1)))
                    .andReturn();
            return result.getResponse().getStatus();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<Integer> runConcurrently(int attempts, Attempt attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                int attemptIndex = index;
                futures.add(pool.submit(() -> {
                    start.await();
                    return attempt.run(attemptIndex);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private void provision() throws Exception {
        mockMvc.perform(post("/api/internal/inventory/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.inventory(general.toString(), "25.00", CAPACITY)))
                .andExpect(status().isCreated());
    }

    private void assertInventory(int available, int held, int sold) {
        Map<String, Object> row = jdbc.queryForMap(
                "select capacity, available, held, sold from event_inventory where event_id = ? and ticket_type_id = ?",
                eventId,
                general);
        assertThat(((Number) row.get("capacity")).intValue()).isEqualTo(CAPACITY);
        assertThat(((Number) row.get("available")).intValue()).isEqualTo(available);
        assertThat(((Number) row.get("held")).intValue()).isEqualTo(held);
        assertThat(((Number) row.get("sold")).intValue()).isEqualTo(sold);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    @FunctionalInterface
    private interface Attempt {
        int run(int index) throws Exception;
    }
}
