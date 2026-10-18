package com.example.newaichatapp.agent.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.newaichatapp.agent.model.*;
import com.example.newaichatapp.agent.service.*;
import com.example.newaichatapp.agent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    private final ChatService chatService;
    private final OllamaClient ollamaClient;
    private final TaskExecutor workers;

    public ChatController(ChatService chatService, OllamaClient ollamaClient,
                           @Qualifier("agentTaskExecutor") TaskExecutor workers) {
        this.chatService = chatService;
        this.ollamaClient = ollamaClient;
        this.workers = workers;
    }

    /**
     * GET /api/chat/stream?runId=...&message=...&history=<json array of {role,content}, optional>
     * Streams the model's reply as SSE "token" events (one per chunk Ollama emits), ending with a
     * "done" event carrying the full text, or an "error" event if generation failed.
     * A GET (not POST) is used because EventSource, the standard browser SSE client, only issues GET.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String runId,
                              @RequestParam String message,
                              @RequestParam(required = false) String history) {
        SseEmitter emitter = new SseEmitter(0L);
        List<ChatTurn> parsedHistory = parseHistory(history);

        workers.execute(() -> {
            running.put(runId, Thread.currentThread());
            log.info("Chat run started id={}", runId);
            try {
                chatService.chatStreaming(message, parsedHistory, token -> sendToken(emitter, token));
                sendDone(emitter);
                log.info("Chat run complete id={}", runId);
                emitter.complete();
            } catch (Exception ex) {
                log.error("Chat run failed id={}", runId, ex);
                sendError(emitter, ex.getMessage());
                emitter.complete();
            } finally {
                running.remove(runId);
            }
        });

        return emitter;
    }

    @PostMapping("/stop/{runId}")
    public Map<String, Object> stop(@PathVariable String runId) {
        Thread worker = running.get(runId);
        if (worker == null) return Map.of("stopped", false, "message", "Run not found or already completed");
        log.info("Chat cancellation requested runId={}", runId);
        worker.interrupt();
        ollamaClient.cancel(worker);
        return Map.of("stopped", true);
    }

    private List<ChatTurn> parseHistory(String historyJson) {
        // Deliberately minimal, dependency-free parsing: the UI only ever sends a flat array of
        // {"role":"...","content":"..."} objects it built itself, so a tiny hand-rolled reader avoids
        // pulling in a second JSON library just for this one optional query param.
        List<ChatTurn> turns = new ArrayList<>();
        if (historyJson == null || historyJson.isBlank()) return turns;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode arr = mapper.readTree(historyJson);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    String role = node.has("role") ? node.get("role").asText() : "user";
                    String content = node.has("content") ? node.get("content").asText() : "";
                    if (!content.isBlank()) turns.add(new ChatTurn(role, content));
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse chat history param, ignoring it: {}", e.getMessage());
        }
        return turns;
    }

    private void sendToken(SseEmitter emitter, String token) {
        try {
            emitter.send(SseEmitter.event().name("token").data(Map.of("text", token), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // client likely disconnected; the worker keeps reading from Ollama regardless
        }
    }

    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(Map.of("done", true), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) { }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("message", message == null ? "Unknown error" : message), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) { }
    }
}
