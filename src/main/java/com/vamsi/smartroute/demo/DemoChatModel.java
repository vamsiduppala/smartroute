package com.vamsi.smartroute.demo;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * A stand-in {@link ChatModel} used only under the {@code demo} Spring profile, so the whole
 * routing + gateway pipeline runs end-to-end with <b>no OpenAI API key and no network calls</b>.
 *
 * It returns a deterministic, clearly-labelled canned answer that echoes the tier the router
 * selected for this request (read from the per-request model override on the {@link Prompt}), and
 * reports plausible token usage so the cost math downstream is real. The answer is always
 * non-empty, so it passes the default validator at the starting tier — demonstrating the
 * cheapest-tier-wins happy path without a live model.
 */
public class DemoChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        String tierModel = prompt.getOptions() != null && prompt.getOptions().getModel() != null
                ? prompt.getOptions().getModel()
                : "gpt-5.6-luna";
        String userText = prompt.getContents() == null ? "" : prompt.getContents();

        String answer = "[SmartRoute demo — no live model call] \"" + truncate(userText, 80)
                + "\" would be answered here by " + tierModel
                + ". Set OPENAI_API_KEY and run without the 'demo' profile for real routing.";

        int promptTokens = Math.max(1, userText.length() / 4);   // ~4 chars/token
        int completionTokens = 32;
        DefaultUsage usage = new DefaultUsage(promptTokens, completionTokens);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();

        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        throw new UnsupportedOperationException("The demo profile does not support streaming responses.");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
