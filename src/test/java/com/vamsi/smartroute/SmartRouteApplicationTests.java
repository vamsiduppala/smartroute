package com.vamsi.smartroute;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Full application-context smoke test. Every other test in this project exercises a single
 * module (unit test) or a @WebMvcTest slice (one controller + its declared collaborators) --
 * nothing had ever proven the WHOLE bean graph actually wires together: routing + gateway +
 * governance + guardrails + observability + the OpenAPI config, all at once. A missing bean,
 * circular dependency, or bad property would only surface at real `mvn spring-boot:run` time.
 *
 * OpenAiChatModel is mocked so this needs no real API key or network call; the api-key property
 * still needs SOME value because application.yml references ${OPENAI_API_KEY} with no default,
 * and that placeholder is resolved before bean creation (and thus before @MockitoBean applies).
 */
@SpringBootTest
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key-context-load-only")
class SmartRouteApplicationTests {

    @MockitoBean
    private OpenAiChatModel chatModel;

    @Test
    void contextLoads() {
        // Intentionally empty: a context that fails to start fails this test with the real
        // cause in the stack trace (missing bean, misconfigured property, circular dependency).
    }
}
