package com.vamsi.smartroute.web;

import com.vamsi.smartroute.model.Tier;
import com.vamsi.smartroute.routing.RouteResult;
import com.vamsi.smartroute.routing.SmartRouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    void malformedJsonBodyIsRejectedAsBadRequest() throws Exception {
        mvc.perform(post("/route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongHttpMethodGetsMethodNotAllowedNotServerError() throws Exception {
        // /route is POST-only; Spring normally maps this to 405, but GlobalExceptionHandler's
        // broad Exception catch-all can shadow that the same way it shadowed 400 for malformed
        // JSON -- same bug class, different exception type.
        mvc.perform(get("/route"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void methodNotAllowedResponseIncludesAllowHeader() throws Exception {
        // RFC 7231: a 405 response should list the methods that ARE supported. Spring's own
        // default handling does this via HttpRequestMethodNotSupportedException.getHeaders();
        // a hand-built ResponseEntity that ignores that will silently drop it.
        mvc.perform(get("/route"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"));
    }

    @Test
    void unsupportedContentTypeGetsUnsupportedMediaTypeNotServerError() throws Exception {
        mvc.perform(post("/route")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("prompt=hi"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void unknownPathGetsNotFoundNotServerError() throws Exception {
        // An unmapped URL throws NoResourceFoundException on Spring 6.1+; without an explicit
        // handler the broad Exception catch-all shadows it into a misleading 500. A plain wrong
        // URL should be a clean 404 -- same shadowing bug class as the 400/405/415 cases above.
        mvc.perform(get("/no-such-endpoint"))
                .andExpect(status().isNotFound());
    }

    // Note: a second-pass review also flagged HttpMediaTypeNotAcceptableException (bad Accept
    // header -> 406) as a sibling gap to the 415 case above. Written as a test (POST /route
    // with Accept: application/xml) and it does NOT reproduce here -- actual result was 200,
    // not 500 or 406. This app has no XML converter and no explicit `produces` on the mapping,
    // so Spring's default content negotiation doesn't reject the mismatched Accept header the
    // way the reviewer's general Spring knowledge predicted. Not fixing a bug that doesn't
    // actually exist in this configuration -- verified by running the test, not assumed.

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
