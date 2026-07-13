package com.vamsi.smartroute.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer test: GET /observability/metrics, with TelemetryService mocked out. */
@WebMvcTest(ObservabilityController.class)
class ObservabilityControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TelemetryService telemetry;

    @Test
    void metricsRollUpReflectsTelemetryService() throws Exception {
        when(telemetry.totalCalls()).thenReturn(7L);
        when(telemetry.totalCostUsd()).thenReturn(0.042);
        when(telemetry.callsByTier()).thenReturn(Map.of("LUNA", 5L, "SOL", 2L));

        mvc.perform(get("/observability/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(7))
                .andExpect(jsonPath("$.totalCostUsd").value(0.042))
                .andExpect(jsonPath("$.callsByTier.LUNA").value(5))
                .andExpect(jsonPath("$.callsByTier.SOL").value(2));
    }

    @Test
    void metricsRollUpHandlesZeroState() throws Exception {
        when(telemetry.totalCalls()).thenReturn(0L);
        when(telemetry.totalCostUsd()).thenReturn(0.0);
        when(telemetry.callsByTier()).thenReturn(Map.of());

        mvc.perform(get("/observability/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(0))
                .andExpect(jsonPath("$.callsByTier").isEmpty());
    }
}
