package com.guanghe.backend.common.observability;

import com.guanghe.backend.common.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "management.tracing.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "management.otlp.tracing.export.enabled=true",
        "management.otlp.tracing.endpoint=http://127.0.0.1:1/v1/traces",
        "management.otlp.tracing.connect-timeout=100ms",
        "management.otlp.tracing.timeout=100ms"
})
@AutoConfigureMockMvc
@ActiveProfiles("mock")
class OpenTelemetryFailureIsolationIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unavailableCollectorDoesNotFailBusinessRequestAndKeepsInboundParent() throws Exception {
        String traceId = "1".repeat(32);
        mockMvc.perform(get("/actuator/health")
                        .header("traceparent", "00-" + traceId + "-" + "2".repeat(16) + "-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, traceId));
    }
}
