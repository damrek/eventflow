package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

/**
 * Reservation contract against a real PostgreSQL container. Each test also proves that Flyway
 * and the JPA mappings agree, including the {@code available + held + sold = capacity} check.
 */
@SpringBootTest(properties = "booking.expiry.interval=PT1H")
@AutoConfigureMockMvc
@Testcontainers
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReservationApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsReservationThatHoldsUnits() throws Exception {
        provision();

        MvcResult created = mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservationTwoTypes(
                                eventId.toString(), general.toString(), 2, vip.toString(), 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.userId").value(ApiFixtures.USER))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalAmount").value(110.0))
                .andReturn();

        assertThat(JsonPath.<String>read(created.getResponse().getContentAsString(), "$.expiresAt")).isNotBlank();

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andReturn();
        assertThat(availabilityOf(availability, general, "available")).isEqualTo(398);
        assertThat(availabilityOf(availability, general, "held")).isEqualTo(2);
        assertThat(availabilityOf(availability, vip, "held")).isEqualTo(1);
    }

    @Test
    void refusesToHoldMoreUnitsThanAvailable() throws Exception {
        provision();

        mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 401)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_TICKETS"));

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andReturn();
        assertThat(availabilityOf(availability, general, "held")).isZero();
    }

    @Test
    void requiresUserAndIdempotencyKey() throws Exception {
        provision();

        mockMvc.perform(post("/api/reservations")
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_ID_REQUIRED"));

        mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void replaysTheOriginalResponseForTheSameKeyAndPayload() throws Exception {
        provision();

        String firstReservation = createReservation("order-1", general, 2);
        String replayReservation = createReservation("order-1", general, 2);

        assertThat(replayReservation).isEqualTo(firstReservation);

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andReturn();
        assertThat(availabilityOf(availability, general, "held")).isEqualTo(2);
    }

    @Test
    void rejectsTheSameKeyWithADifferentPayload() throws Exception {
        provision();
        createReservation("order-1", general, 2);

        mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void readsOnlyTheOwnersReservation() throws Exception {
        provision();
        String reservationId = createReservation("order-1", general, 1);

        mockMvc.perform(get("/api/reservations/{id}", reservationId).header("X-User-Id", ApiFixtures.USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId))
                .andExpect(jsonPath("$.status").value("HELD"));

        mockMvc.perform(get("/api/reservations/{id}", reservationId).header("X-User-Id", ApiFixtures.OTHER_USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void cancelsReservationAndReturnsUnits() throws Exception {
        provision();
        String reservationId = createReservation("order-1", general, 2);

        mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId).header("X-User-Id", ApiFixtures.USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andReturn();
        assertThat(availabilityOf(availability, general, "available")).isEqualTo(400);
        assertThat(availabilityOf(availability, general, "held")).isZero();

        mockMvc.perform(post("/api/reservations/{id}/cancel", reservationId).header("X-User-Id", ApiFixtures.USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));
    }

    @Test
    void rejectsUnknownEventInventoryForAReservation() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidReservationBody() throws Exception {
        provision();

        mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", "order-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", "order-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), general.toString(), 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void echoesTheRequestIdInProblemDetails() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/api/reservations/{id}", unknown)
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("X-Request-Id", "trace-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "trace-123"))
                .andExpect(jsonPath("$.traceId").value("trace-123"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.instance").value("/api/reservations/" + unknown));
    }

    private void provision() throws Exception {
        mockMvc.perform(post("/api/internal/inventory/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.inventoryTwoTypes(general.toString(), vip.toString())))
                .andExpect(status().isCreated());
    }

    private String createReservation(String key, UUID ticketTypeId, int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header("X-User-Id", ApiFixtures.USER)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.reservation(eventId.toString(), ticketTypeId.toString(), quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.reservationId");
    }

    private int availabilityOf(MvcResult result, UUID ticketTypeId, String field) throws Exception {
        List<Integer> values = JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.ticketTypes[?(@.ticketTypeId=='" + ticketTypeId + "')]." + field);
        assertThat(values).hasSize(1);
        return values.getFirst();
    }
}
