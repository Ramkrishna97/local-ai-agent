package com.example.newaichatapp.agent.service;

import com.example.newaichatapp.agent.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class TaskRouter {

    private static final Logger log = LoggerFactory.getLogger(TaskRouter.class);

    private static final Pattern CODE_REQUEST = Pattern.compile(
            "(?i)\\b(code|coding|java|spring boot|function|method|class|algorithm|api|program|implement|refactor|debug|compile|unit test|junit|python|javascript|typescript|sql|html|css|build an app)\\b");
    private static final Pattern CONVERSATION = Pattern.compile(
            "(?i)\\b(story|poem|joke|explain|summarize|translate|recipe|hello|hi|hey|advice|what is|who is|tell me|write an email)\\b");
    private final OllamaClient ollama;

    public TaskRouter(OllamaClient ollama) { this.ollama = ollama; }

    public boolean isCodeTask(String prompt) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Prompt is required");
        if (CODE_REQUEST.matcher(prompt).find()) {
            log.info("TaskRouter: classified as CODE via keyword match");
            return true;
        }
        if (CONVERSATION.matcher(prompt).find()) {
            log.info("TaskRouter: classified as DIRECT via keyword match");
            return false;
        }
        String classification = ollama.generate("Classify this user request. Answer ONLY CODE or DIRECT. " +
                "CODE means the user explicitly wants source code, a program, debugging, or programming implementation. " +
                "DIRECT means a natural-language answer or creative writing.\nRequest: " + prompt, false);
        boolean isCode = classification.strip().toUpperCase(Locale.ROOT).startsWith("CODE");
        log.info("TaskRouter: classified as {} via model call", isCode ? "CODE" : "DIRECT");
        return isCode;
    }

    public String answer(String prompt) {
        log.info("TaskRouter: generating direct answer");
        return ollama.generate("Respond directly and completely to the user's request. " +
                "Do not output Java source unless explicitly requested.\n\nUser: " + prompt, false);
    }
}
