package com.eventflow.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * Inventory slice over real HTTP and real PostgreSQL: provisioning is create-once and
 * availability is the public read the SPA will use.
 */
@SpringBootTest(properties = "booking.expiry.interval=PT1H")
@AutoConfigureMockMvc
@Testcontainers
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InventoryApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private final UUID eventId = UUID.randomUUID();
    private final UUID general = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void provisionsInventoryAndExposesAvailability() throws Exception {
        mockMvc.perform(post("/api/internal/inventory/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.inventoryTwoTypes(general.toString(), vip.toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.ticketTypes", hasSize(2)));

        MvcResult availability = mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(availabilityOf(availability, general, "available")).isEqualTo(400);
        assertThat(availabilityOf(availability, general, "held")).isZero();
        assertThat(availabilityOf(availability, vip, "capacity")).isEqualTo(50);
    }

    @Test
    void identicalProvisioningReplayIsAcceptedWithoutDuplicatingRows() throws Exception {
        provision();

        mockMvc.perform(post("/api/internal/inventory/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.inventoryTwoTypes(general.toString(), vip.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketTypes", hasSize(2)));
    }

    @Test
    void refusesToReprovisionWithADifferentOffer() throws Exception {
        provision();

        mockMvc.perform(post("/api/internal/inventory/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.inventory(general.toString(), "25.00", 500)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_ALREADY_PROVISIONED"));
    }

    @Test
    void rejectsInvalidProvisioningPayload() throws Exception {
        mockMvc.perform(post("/api/internal/inventory/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/internal/inventory/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.inventoryTwoTypes(general.toString(), general.toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVENTORY_DUPLICATE_TICKET_TYPE"));
    }

    @Test
    void returnsNotFoundForUnknownInventory() throws Exception {
        mockMvc.perform(get("/api/events/{eventId}/availability", eventId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void exposesOpenApiDescription() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("EventFlow Booking API"))
                .andExpect(jsonPath("$.paths").isMap());
    }

    private void provision() throws Exception {
        mockMvc.perform(post("/api/internal/inventory/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiFixtures.inventoryTwoTypes(general.toString(), vip.toString())))
                .andExpect(status().isCreated());
    }

    private int availabilityOf(MvcResult result, UUID ticketTypeId, String field) throws Exception {
        List<Integer> values = JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.ticketTypes[?(@.ticketTypeId=='" + ticketTypeId + "')]." + field);
        assertThat(values).hasSize(1);
        return values.getFirst();
    }
}
