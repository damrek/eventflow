package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.eventflow.booking.reservation.ReservationExpiryWorker;
import com.eventflow.booking.inventory.InventoryItemSpec;
import com.eventflow.booking.inventory.InventoryService;
import com.jayway.jsonpath.JsonPath;

/**
 * Expiration is the only path that returns units without a client asking for it, so it is
 * tested through the real worker against real PostgreSQL.
 */
@SpringBootTest(properties = {
        "booking.expiry.interval=PT1H",
        "eventflow.messaging.outbox.interval=200ms",
        "eventflow.messaging.retry.delays=200ms,200ms,200ms",
        "spring.rabbitmq.connection-timeout=2s"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReservationExpiryTest extends BookingIntegrationTest {


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationExpiryWorker worker;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private MeterRegistry registry;

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();

    @Test
    void expiresDueReservationsAndReleasesUnits() throws Exception {
        provision(10);
        String reservationId = createReservation(3);
        makeDue(reservationId);

        double expired = registry.find("booking.expired").counter().count();

        assertThat(worker.expireDue()).isEqualTo(1);
        assertThat(registry.find("booking.expired").counter().count()).isEqualTo(expired + 1);

        mockMvc.perform(get("/api/reservations/{id}", reservationId).header("X-User-Id", ApiFixtures.USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(availableOf(availability)).isEqualTo(10);
        assertThat(heldOf(availability)).isZero();

        mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId).header("X-User-Id", ApiFixtures.USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));
    }

    @Test
    void keepsReservationsBeforeTheirDeadline() throws Exception {
        provision(10);
        createReservation(2);

        assertThat(worker.expireDue()).isZero();

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andReturn();
        assertThat(heldOf(availability)).isEqualTo(2);
    }

    @Test
    void doesNotReleaseUnitsTwice() throws Exception {
        provision(10);
        String reservationId = createReservation(4);
        makeDue(reservationId);

        assertThat(worker.expireDue()).isEqualTo(1);
        assertThat(worker.expireDue()).isZero();

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andReturn();
        assertThat(availableOf(availability)).isEqualTo(10);
        assertThat(heldOf(availability)).isZero();
    }

    @Test
    void skipsCancelledReservations() throws Exception {
        provision(10);
        String reservationId = createReservation(1);

        mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId).header("X-User-Id", ApiFixtures.USER))
                .andExpect(status().isOk());
        makeDue(reservationId);

        assertThat(worker.expireDue()).isZero();

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andReturn();
        assertThat(availableOf(availability)).isEqualTo(10);
    }

    private void provision(int capacity) {
        inventory.provision(eventId, List.of(new InventoryItemSpec(general, new BigDecimal("25.00"), capacity)));
    }

    private String createReservation(int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", "order-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.reservationId");
    }

    private void makeDue(String reservationId) {
        int updated = jdbc.update(
                "update reservation set expires_at = now() - interval '1 minute' where id = ?",
                UUID.fromString(reservationId));
        assertThat(updated).isEqualTo(1);
    }

    private List<Integer> availability(MvcResult result, String field) throws Exception {
        return JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.ticketTypes[?(@.ticketTypeId=='" + general + "')]." + field);
    }

    private int availableOf(MvcResult result) throws Exception {
        return availability(result, "available").getFirst();
    }

    private int heldOf(MvcResult result) throws Exception {
        return availability(result, "held").getFirst();
    }
}
