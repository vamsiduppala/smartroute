package com.vamsi.smartroute.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.*;

/** The demo stand-in must produce a usable, non-empty response so the offline pipeline behaves like the real one. */
class DemoChatModelTest {

    private final DemoChatModel model = new DemoChatModel();

    @Test
    void returnsANonEmptyCannedAnswerEchoingTheSelectedTier() {
        var options = OpenAiChatOptions.builder().model("gpt-5.6-terra").build();
        ChatResponse r = model.call(new Prompt("What is 2 + 2?", options));

        String text = r.getResult().getOutput().getText();
        assertNotNull(text);
        assertFalse(text.isBlank(), "must be non-empty so the default validator accepts it");
        assertTrue(text.contains("gpt-5.6-terra"), "answer should name the tier the router selected");

        // Token usage is populated so the downstream cost math is real, not zero.
        Object promptTokens = r.getMetadata().getUsage().getPromptTokens();
        assertTrue(((Number) promptTokens).longValue() > 0);
    }

    @Test
    void doesNotSupportStreaming() {
        var options = OpenAiChatOptions.builder().model("gpt-5.6-luna").build();
        assertThrows(UnsupportedOperationException.class,
                () -> model.stream(new Prompt("hi", options)));
    }
}
