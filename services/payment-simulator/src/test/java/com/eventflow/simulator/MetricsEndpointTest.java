package com.eventflow.simulator;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prometheus scrapes this endpoint too (Phase 7): the simulator is part of the flow the dashboards
 * describe, so a missing registry here would leave the provider hop without JVM and HTTP meters.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesPrometheusMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }
}
