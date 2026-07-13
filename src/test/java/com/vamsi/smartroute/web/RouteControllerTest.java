package com.vamsi.smartroute.web;

import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer test: POST /route, with SmartRouteService mocked out — no live model call. */
@WebMvcTest(RouteController.class)
class RouteControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SmartRouteService router;

    @Test
    void routesPromptAndReturnsTierResult() throws Exception {
        when(router.route(any(), any())).thenReturn(
                new RouteResult("Paris", Tier.LUNA, Tier.LUNA, 1, 10, 5, 0.0001, true, "simple"));

        mvc.perform(post("/route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"What is the capital of France?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Paris"))
                .andExpect(jsonPath("$.tierUsed").value("LUNA"))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void blankPromptIsRejectedWithoutReachingTheRouter() throws Exception {
        mvc.perform(post("/route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("prompt must not be blank"));
    }

    @Test
    void surfacesEscalationInResponse() throws Exception {
        when(router.route(any(), any())).thenReturn(
                new RouteResult("42", Tier.SOL, Tier.SOL, 3, 40, 20, 0.0009, true, "hard"));

        mvc.perform(post("/route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"Prove the Collatz conjecture.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tierUsed").value("SOL"))
                .andExpect(jsonPath("$.attempts").value(3));
    }
}
