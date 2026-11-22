package com.eventflow.catalog.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
 * End-to-end HTTP contract against a real PostgreSQL container. Flyway migrations create the
 * schema, so every test also proves that migrations and JPA mappings agree.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql(scripts = "/db/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CatalogApiIntegrationTest {

    private static final String GENERAL_TICKETS = """
            [{"name":"General","price":25.00,"capacity":400},
             {"name":"VIP","price":60.00,"capacity":50}]""";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsVenueAndListsIt() throws Exception {
        String venueId = createVenue("Arena Sur", 5000);

        mockMvc.perform(get("/api/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(venueId))
                .andExpect(jsonPath("$[0].name").value("Arena Sur"))
                .andExpect(jsonPath("$[0].capacity").value(5000));
    }

    @Test
    void createsDraftEventWithTicketTypes() throws Exception {
        String venueId = createVenue("Arena Sur", 5000);

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Java Meetup", venueId, "2030-05-01T19:00:00Z", GENERAL_TICKETS)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.totalCapacity").value(450))
                .andExpect(jsonPath("$.ticketTypes", hasSize(2)))
                .andExpect(jsonPath("$.ticketTypes[0].name").value("General"))
                .andExpect(jsonPath("$.ticketTypes[0].capacity").value(400))
                .andExpect(jsonPath("$.venueName").value("Arena Sur"));
    }

    @Test
    void returnsEventDetailWithTicketTypes() throws Exception {
        String venueId = createVenue("Arena Sur", 5000);
        String eventId = createEvent(venueId, "Java Meetup", "2030-05-01T19:00:00Z", GENERAL_TICKETS);

        mockMvc.perform(get("/api/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.startsAt").value("2030-05-01T19:00:00Z"))
                .andExpect(jsonPath("$.ticketTypes", hasSize(2)));
    }

    @Test
    void hidesDraftEventsFromPublicListing() throws Exception {
        String venueId = createVenue("Arena Sur", 5000);
        createEvent(venueId, "Java Meetup", "2030-05-01T19:00:00Z", GENERAL_TICKETS);

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void publishesEventAndListsItOnce() throws Exception {
        String venueId = createVenue("Arena Sur", 5000);
        String eventId = createEvent(venueId, "Java Meetup", "2030-05-01T19:00:00Z", GENERAL_TICKETS);

        mockMvc.perform(post("/api/events/{eventId}/publish", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(eventId))
                .andExpect(jsonPath("$.content[0].venueName").value("Arena Sur"));

        mockMvc.perform(post("/api/events/{eventId}/publish", eventId))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_PUBLISHED"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.instance").value("/api/events/" + eventId + "/publish"));
    }

    @Test
    void rejectsPublishingEventThatAlreadyStarted() throws Exception {
        String venueId = createVenue("Arena Sur", 5000);
        String eventId = createEvent(venueId, "Past Meetup", "2020-01-01T19:00:00Z", GENERAL_TICKETS);

        mockMvc.perform(post("/api/events/{eventId}/publish", eventId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EVENT_START_IN_PAST"));
    }

    @Test
    void filtersListingByStartDateWindow() throws Exception {
        String venueId = createVenue("Arena Sur", 5000);
        String first = createEvent(venueId, "First", "2030-01-01T19:00:00Z", GENERAL_TICKETS);
        String second = createEvent(venueId, "Second", "2031-01-01T19:00:00Z", GENERAL_TICKETS);
        publish(first);
        publish(second);

        mockMvc.perform(get("/api/events")
                        .param("from", "2030-06-01T00:00:00Z")
                        .param("to", "2031-06-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(second));

        mockMvc.perform(get("/api/events").param("to", "2030-01-01T19:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void paginatesListing() throws Exception {
        String venueId = createVenue("Arena Sur", 5000);
        publish(createEvent(venueId, "First", "2030-01-01T19:00:00Z", GENERAL_TICKETS));
        publish(createEvent(venueId, "Second", "2030-01-02T19:00:00Z", GENERAL_TICKETS));
        publish(createEvent(venueId, "Third", "2030-01-03T19:00:00Z", GENERAL_TICKETS));

        mockMvc.perform(get("/api/events").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].name").value("First"));

        mockMvc.perform(get("/api/events").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Third"));
    }

    @Test
    void rejectsEventExceedingVenueCapacity() throws Exception {
        String venueId = createVenue("Small Room", 100);

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Too Big", venueId, "2030-05-01T19:00:00Z", GENERAL_TICKETS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EVENT_CAPACITY_EXCEEDED"));
    }

    @Test
    void rejectsUnknownVenue() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Ghost", UUID.randomUUID().toString(), "2030-05-01T19:00:00Z", GENERAL_TICKETS)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VENUE_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidBodyWithProblemDetails() throws Exception {
        mockMvc.perform(post("/api/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"address\":\"Street\",\"capacity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("urn:eventflow:error:validation_failed"))
                .andExpect(jsonPath("$.errors", hasSize(2)));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void rejectsInvalidPaginationParameter() throws Exception {
        mockMvc.perform(get("/api/events").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsInvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/events")
                        .param("from", "2031-01-01T00:00:00Z")
                        .param("to", "2030-01-01T00:00:00Z"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void rejectsInvalidUuidPath() throws Exception {
        mockMvc.perform(get("/api/events/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void returnsNotFoundForUnknownEvent() throws Exception {
        mockMvc.perform(get("/api/events/{eventId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void returnsNotFoundForUnknownPath() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void echoesRequestIdInHeaderAndProblemDetails() throws Exception {
        mockMvc.perform(get("/api/events/{eventId}", UUID.randomUUID()).header("X-Request-Id", "test-correlation-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "test-correlation-1"))
                .andExpect(jsonPath("$.traceId").value("test-correlation-1"));
    }

    @Test
    void ignoresUnsafeRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/events/{eventId}", UUID.randomUUID())
                        .header("X-Request-Id", "<script>alert(1)</script>"))
                .andExpect(status().isNotFound())
                .andReturn();

        String generated = result.getResponse().getHeader("X-Request-Id");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String traceId = JsonPath.read(body, "$.traceId");

        org.assertj.core.api.Assertions.assertThat(generated).isNotBlank().isNotEqualTo("<script>alert(1)</script>");
        org.assertj.core.api.Assertions.assertThat(traceId).isEqualTo(generated);
    }

    @Test
    void exposesOpenApiDescription() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/events")))
                .andExpect(content().string(containsString("/api/venues")));
    }

    @Test
    void reportsHealthWithDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private String createVenue(String name, int capacity) throws Exception {
        String body = """
                {"name":"%s","address":"Main street 1","capacity":%d}""".formatted(name, capacity);
        MvcResult result = mockMvc.perform(post("/api/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result, "$.id");
    }

    private String createEvent(String venueId, String name, String startsAt, String ticketTypes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(name, venueId, startsAt, ticketTypes)))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result, "$.id");
    }

    private void publish(String eventId) throws Exception {
        mockMvc.perform(post("/api/events/{eventId}/publish", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    private static String eventJson(String name, String venueId, String startsAt, String ticketTypes) {
        return """
                {"name":"%s","venueId":"%s","startsAt":"%s","ticketTypes":%s}"""
                .formatted(name, venueId, startsAt, ticketTypes);
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
