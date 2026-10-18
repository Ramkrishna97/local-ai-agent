package com.example.newaichatapp.agent.service;

import com.example.newaichatapp.agent.model.*;
import com.example.newaichatapp.agent.service.*;
import com.example.newaichatapp.agent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * Chat mode: just passes the user's message (plus recent history, if any) straight to the model and
 * streams the answer back. No planning, no steps, no review loop — this is the "talk to the model"
 * path, kept deliberately separate from the agent/planner pipeline in StepExecutor.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Cap on how much prior conversation we inline into the prompt, to keep local-model context sane. */
    private static final int MAX_HISTORY_TURNS = 12;
    private static final int MAX_HISTORY_CHARS = 6000;

    private final OllamaClient ollama;

    public ChatService(OllamaClient ollama) {
        this.ollama = ollama;
    }

    /**
     * Streams the model's reply token-by-token via onToken, and returns the full text once done.
     * Logs the user's message and the outcome (success/failure + length) for console monitoring.
     */
    public String chatStreaming(String message, List<ChatTurn> history, Consumer<String> onToken) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Message is required");

        log.info("Chat message received: \"{}\"", truncateForLog(message));

        String prompt = buildPrompt(message, history);
        try {
            String full = ollama.generateStreaming(prompt, onToken);
            log.info("Chat response generated OK: {} chars", full.length());
            return full;
        } catch (Exception e) {
            log.error("Chat response generation failed", e);
            throw e;
        }
    }

    private String buildPrompt(String message, List<ChatTurn> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful, direct assistant having a conversation with the user. ")
          .append("Answer naturally. When you include code, put it in a fenced code block with the ")
          .append("correct language tag (for example ```java or ```python) so it can be told apart ")
          .append("from your normal explanation text. Keep explanations concise unless asked for detail.\n\n");

        if (history != null && !history.isEmpty()) {
            List<ChatTurn> recent = history.size() > MAX_HISTORY_TURNS
                    ? history.subList(history.size() - MAX_HISTORY_TURNS, history.size())
                    : history;
            StringBuilder historyBlock = new StringBuilder();
            for (ChatTurn turn : recent) {
                if (turn == null || turn.content == null || turn.content.isBlank()) continue;
                String role = "user".equalsIgnoreCase(turn.role) ? "User" : "Assistant";
                historyBlock.append(role).append(": ").append(turn.content).append("\n");
            }
            String trimmedHistory = historyBlock.length() > MAX_HISTORY_CHARS
                    ? historyBlock.substring(historyBlock.length() - MAX_HISTORY_CHARS)
                    : historyBlock.toString();
            if (!trimmedHistory.isBlank()) {
                sb.append("Conversation so far:\n").append(trimmedHistory).append("\n");
            }
        }

        sb.append("User: ").append(message).append("\nAssistant:");
        return sb.toString();
    }

    private String truncateForLog(String s) {
        if (s.length() <= 120) return s;
        return s.substring(0, 120) + "...";
    }
}
