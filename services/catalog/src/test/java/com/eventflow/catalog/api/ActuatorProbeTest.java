package com.eventflow.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins what the deployment depends on: the Compose healthcheck calls {@code /actuator/health} and
 * the Kubernetes probes call the {@code liveness} and {@code readiness} groups. Liveness must never
 * depend on an external system, while readiness must fail when the database this replica owns is
 * unreachable, so an instance without its schema never takes traffic.
 */
class ActuatorProbeTest extends CatalogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HealthEndpointGroups healthGroups;

    @Test
    void exposesLivenessAndReadinessProbes() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessCoversTheDatabaseAndLivenessDoesNot() {
        assertThat(healthGroups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(healthGroups.get("readiness").isMember("db")).isTrue();
        assertThat(healthGroups.get("liveness").isMember("db")).isFalse();
    }

    @Test
    void aggregateHealthIsUpWithTheRealDatabaseAndBroker() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
