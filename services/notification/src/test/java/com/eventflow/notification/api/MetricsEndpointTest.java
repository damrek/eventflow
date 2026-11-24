package com.eventflow.notification.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prometheus scrapes this endpoint (Phase 7). The stack goes silently blind if the registry is
 * missing from the classpath or the endpoint is not exposed, so both are pinned here.
 */
class MetricsEndpointTest extends NotificationIntegrationTest {

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
