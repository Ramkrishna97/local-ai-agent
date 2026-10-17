package com.example.newaichatapp.agent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central tunables and the mutable "active model" state used by every
 * planning/fix call. Changed at runtime via /api/models/select.
 */
public final class AgentConfig {

    private AgentConfig() {}

    public static final String OLLAMA_BASE_URL =
            System.getProperty("ollama.baseUrl", "http://localhost:11434");
    public static final int MAX_RETRIES_PER_STEP =
            Integer.getInteger("agent.maxRetries", 3);
    public static final int PROCESS_TIMEOUT_SECONDS =
            Integer.getInteger("agent.processTimeoutSeconds", 20);
    public static final int OLLAMA_READ_TIMEOUT_MS =
            Integer.getInteger("ollama.readTimeoutMs", 180_000);
    public static final int OLLAMA_LOAD_TIMEOUT_MS =
            Integer.getInteger("ollama.loadTimeoutMs", 120_000);

    /**
     * Where the selected model name is persisted to disk, so it survives an
     * app restart (devtools reload, IDE re-run, etc). Plain text file
     * containing just the model name.
     */
    private static final Path ACTIVE_MODEL_FILE = Paths.get(
            System.getProperty("agent.stateDir", System.getProperty("java.io.tmpdir")),
            "planner-agent-active-model.txt");
    /**
     * When true (default), a step whose test could not be made to pass after
     * MAX_RETRIES_PER_STEP attempts does NOT abort the whole run — the pipeline
     * continues to later steps so you still get their generated code.
     * Set -Dagent.continueOnFailure=false to restore the old "stop on first failure" behaviour.
     */
    public static final boolean CONTINUE_ON_STEP_FAILURE =
            Boolean.parseBoolean(System.getProperty("agent.continueOnFailure", "true"));

    /**
     * When true (default), generatedCode is only compiled, never run on its own.
     * Running it standalone is not required — only the test needs a main().
     * Set -Dagent.runGeneratedCode=false to skip running generatedCode's main() even if it has one.
     */
    public static final boolean RUN_GENERATED_CODE_STANDALONE =
            Boolean.parseBoolean(System.getProperty("agent.runGeneratedCode", "false"));

    /**
     * Resolution order on startup: -Dollama.model system property, then the
     * last selection persisted to ACTIVE_MODEL_FILE, then null.
     */
    private static volatile String activeModel = resolveInitialActiveModel();

    private static String resolveInitialActiveModel() {
        String fromProperty = System.getProperty("ollama.model", null);
        if (fromProperty != null && !fromProperty.isBlank()) return fromProperty;
        try {
            if (Files.exists(ACTIVE_MODEL_FILE)) {
                String persisted = Files.readString(ACTIVE_MODEL_FILE, StandardCharsets.UTF_8).trim();
                if (!persisted.isBlank()) return persisted;
            }
        } catch (IOException ignored) {
            // missing/unreadable state file just means "no prior selection"
        }
        return null;
    }

    public static String getActiveModel() { return activeModel; }

    public static void setActiveModel(String model) {
        activeModel = model;
        try {
            if (model == null || model.isBlank()) {
                Files.deleteIfExists(ACTIVE_MODEL_FILE);
            } else {
                Files.writeString(ACTIVE_MODEL_FILE, model, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // persistence is a convenience, not a correctness requirement
        }
    }
}