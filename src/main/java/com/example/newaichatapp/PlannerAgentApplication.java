//package com.example.newaichatapp;
//
//import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
//import org.springframework.boot.SpringApplication;
//import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.CrossOrigin;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//import org.springframework.web.client.RestTemplate;
//import tools.jackson.databind.ObjectMapper;
//
//import javax.tools.*;
//import java.io.*;
//import java.net.URI;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.*;
//import java.util.*;
//import java.util.concurrent.TimeUnit;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//
//import org.springframework.boot.SpringApplication;
//import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.CrossOrigin;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//import org.springframework.web.client.RestTemplate;
//import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
//
//import javax.tools.*;
//import java.io.*;
//import java.net.URI;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.*;
//import java.util.*;
//import java.util.concurrent.TimeUnit;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
///**
// * Single-file Planner-Agent application.
// *
// * Flow:
// *   1. POST /api/plan/execute { "prompt": "..." }
// *   2. PlannerService asks Ollama for a JSON plan (schema: PlanResponse -> List<PlanStep>)
// *   3. StepExecutor walks step 1..N:
// *        - compile generatedCode (in-memory JavaCompiler)
// *        - run it, capture stdout/exit code
// *        - compile + run testCode, compare against expectedResult
// *        - on any exception: feed the stack trace back to the LLM asking for a fix,
// *          retry up to MAX_RETRIES times before marking the step FAILED and stopping.
// *   4. Returns the full execution trace (what was generated, what ran, pass/fail per step).
// *
// * Run with JDK 8-25 as the IntelliJ project SDK (needs a full JDK, not just a JRE,
// * since javax.tools.ToolProvider.getSystemJavaCompiler() requires one).
// * Requires Ollama running locally (http://localhost:11434) with a model pulled, e.g.:
// *   ollama pull qwen2.5-coder:7b
// */
//@SpringBootApplication
//public class PlannerAgentApplication {
//    public static void main(String[] args) {
//        SpringApplication.run(PlannerAgentApplication.class, args);
//    }
//}
//
//// =====================================================================================
//// ===== CONFIG ========================================================================
//// =====================================================================================
//
//class AgentConfig {
//    static final String OLLAMA_BASE_URL = System.getProperty("ollama.baseUrl", "http://localhost:11434");
//    static final int MAX_RETRIES_PER_STEP = Integer.getInteger("agent.maxRetries", 3);
//    static final int PROCESS_TIMEOUT_SECONDS = Integer.getInteger("agent.processTimeoutSeconds", 20);
//    static final int OLLAMA_READ_TIMEOUT_MS = Integer.getInteger("ollama.readTimeoutMs", 180_000);
//    static final int OLLAMA_LOAD_TIMEOUT_MS = Integer.getInteger("ollama.loadTimeoutMs", 120_000);
//
//    /**
//     * Where the selected model name is persisted to disk, so it survives an app restart (devtools
//     * reload, IDE re-run, etc). Plain text file containing just the model name, nothing else.
//     */
//    private static final Path ACTIVE_MODEL_FILE = Paths.get(
//            System.getProperty("agent.stateDir", System.getProperty("java.io.tmpdir")), "planner-agent-active-model.txt");
//
//    /**
//     * Mutable "currently selected" model, used by every planning/fix call. Changed via /api/models/select.
//     * Resolution order on startup: -Dollama.model system property, then the last selection persisted to
//     * ACTIVE_MODEL_FILE, then null (unset) so the app never silently assumes a model you haven't pulled.
//     */
//    private static volatile String activeModel = resolveInitialActiveModel();
//
//    private static String resolveInitialActiveModel() {
//        String fromProperty = System.getProperty("ollama.model", null);
//        if (fromProperty != null && !fromProperty.isBlank()) return fromProperty;
//        try {
//            if (Files.exists(ACTIVE_MODEL_FILE)) {
//                String persisted = Files.readString(ACTIVE_MODEL_FILE, StandardCharsets.UTF_8).trim();
//                if (!persisted.isBlank()) return persisted;
//            }
//        } catch (IOException ignored) {
//            // fall through to null; a missing/unreadable state file just means "no prior selection"
//        }
//        return null;
//    }
//
//    static String getActiveModel() { return activeModel; }
//
//    static void setActiveModel(String model) {
//        activeModel = model;
//        try {
//            if (model == null || model.isBlank()) {
//                Files.deleteIfExists(ACTIVE_MODEL_FILE);
//            } else {
//                Files.writeString(ACTIVE_MODEL_FILE, model, StandardCharsets.UTF_8);
//            }
//        } catch (IOException ignored) {
//            // persistence is a convenience, not a correctness requirement: in-memory activeModel is still correct
//        }
//    }
//}
//
//// =====================================================================================
//// ===== JSON SCHEMA MODELS (the fixed schema every planning call must produce) =======
//// =====================================================================================
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//class PlanStep {
//    public int stepNumber;
//    public String description;
//    public String generatedCode;      // full compilable Java source for this step
//    public String className;          // public class name inside generatedCode
//    public String testCode;           // full compilable Java source containing a main() that tests generatedCode
//    public String testClassName;      // public class name inside testCode
//    public String expectedResult;     // substring expected in test's stdout, or "EXIT_0" for exit-code-only checks
//    public String status = "PENDING"; // PENDING | PASSED | FAILED
//
//    // execution trace, filled in at runtime (not requested from the LLM)
//    public String compileOutput;
//    public String runOutput;
//    public String testRunOutput;
//    public int attempts = 0;
//    public String lastError;
//}
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//class PlanResponse {
//    public String goal;
//    public List<PlanStep> steps = new ArrayList<>();
//    public String end = "END"; // sentinel marker the LLM is asked to always include
//}
//
//// request/response DTOs for our own REST endpoint
//class ExecuteRequest {
//    public String prompt;
//}
//
//class ExecuteResponse {
//    public String goal;
//    public boolean overallSuccess;
//    public List<PlanStep> steps;
//    public String message;
//}
//
//// =====================================================================================
//// ===== OLLAMA CLIENT =================================================================
//// =====================================================================================
//
//class OllamaRequest {
//    public String model;
//    public String prompt;
//    public boolean stream = false;
//    public String format; // "json" to force structured output
//    public Map<String, Object> options;
//
//    public OllamaRequest(String model, String prompt, boolean forceJson) {
//        this.model = model;
//        this.prompt = prompt;
//        if (forceJson) this.format = "json";
//        Map<String, Object> opts = new HashMap<>();
//        opts.put("temperature", 0.2); // low temp: we want consistent schema + working code, not creativity
//        this.options = opts;
//    }
//}
//
//@JsonIgnoreProperties(ignoreUnknown = true)
//class OllamaResponse {
//    public String response;
//    public boolean done;
//}
//
//class OllamaClient {
//
//    private final RestTemplate restTemplate;
//    private final ObjectMapper mapper = new ObjectMapper();
//
//    OllamaClient() {
//        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
//                new org.springframework.http.client.SimpleClientHttpRequestFactory();
//        factory.setConnectTimeout(10_000);
//        factory.setReadTimeout(AgentConfig.OLLAMA_READ_TIMEOUT_MS);
//        this.restTemplate = new RestTemplate(factory);
//    }
//
//    /** Calls POST /api/generate and returns the raw "response" text from Ollama. */
//    String generate(String prompt, boolean forceJson) {
//        String model = AgentConfig.getActiveModel();
//        if (model == null || model.isBlank()) {
//            throw new IllegalStateException(
//                    "No model selected. Open the Models panel in the UI and select a model first " +
//                            "(or start the app with -Dollama.model=<name>).");
//        }
//
//        String url = AgentConfig.OLLAMA_BASE_URL + "/api/generate";
//        OllamaRequest body = new OllamaRequest(model, prompt, forceJson);
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//        HttpEntity<OllamaRequest> entity = new HttpEntity<>(body, headers);
//
//        try {
//            ResponseEntity<String> raw = restTemplate.postForEntity(URI.create(url), entity, String.class);
//            if (raw.getBody() == null) {
//                throw new IllegalStateException("Empty response body from Ollama at " + url);
//            }
//            // stream=false => Ollama returns a single JSON object, not NDJSON lines
//            OllamaResponse parsed = mapper.readValue(raw.getBody(), OllamaResponse.class);
//            return parsed.response;
//        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
//            throw new RuntimeException("Model '" + model + "' is not pulled in Ollama. Run `ollama pull " +
//                    model + "`, or select a different model from the Models panel.", nf);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to call Ollama at " + url +
//                    " (is it running? model '" + model + "' pulled?): " + e.getMessage(), e);
//        }
//    }
//
//    /**
//     * Loads a model into memory (or unloads it) without generating any real content, by calling
//     * /api/generate with an empty prompt and a keep_alive directive.
//     * Ollama's Go duration parser requires a unit on string values (e.g. "10m", "-1m"), so a bare
//     * "-1" or "0" as a STRING is rejected with "missing unit in duration". Sending keep_alive as a
//     * JSON NUMBER instead sidesteps that parser entirely and is interpreted directly as seconds:
//     *   - keepAliveSeconds = -1  => keep the model resident until explicitly unloaded
//     *   - keepAliveSeconds = 0   => unload the model from memory immediately
//     *   - keepAliveSeconds > 0   => keep resident for that many seconds of inactivity
//     */
//    void setModelKeepAlive(String modelName, long keepAliveSeconds) {
//        String url = AgentConfig.OLLAMA_BASE_URL + "/api/generate";
//        Map<String, Object> body = new HashMap<>();
//        body.put("model", modelName);
//        body.put("prompt", "");
//        body.put("stream", false);
//        body.put("keep_alive", keepAliveSeconds);
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
//
//        org.springframework.http.client.SimpleClientHttpRequestFactory loadFactory =
//                new org.springframework.http.client.SimpleClientHttpRequestFactory();
//        loadFactory.setConnectTimeout(10_000);
//        loadFactory.setReadTimeout(AgentConfig.OLLAMA_LOAD_TIMEOUT_MS);
//        RestTemplate loadTemplate = new RestTemplate(loadFactory);
//
//        try {
//            loadTemplate.postForEntity(URI.create(url), entity, String.class);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to set keep_alive for model '" + modelName +
//                    "' at " + url + ": " + e.getMessage(), e);
//        }
//    }
//}
//
//// =====================================================================================
//// ===== MODEL MANAGEMENT: list / load / unload / select local Ollama models ==========
//// =====================================================================================
//
//class OllamaModelInfo {
//    public String name;
//    public String id;
//    public String size;
//    public String modified;
//}
//
//class ModelActionResponse {
//    public boolean success;
//    public String message;
//    public String activeModel;
//}
//
//class OllamaModelService {
//
//    /** Runs `ollama list` as a subprocess and parses its tabular output. */
//    List<OllamaModelInfo> listModels() {
//        String output = runOllamaCli("list");
//        return parseListOutput(output);
//    }
//
//    /** Runs `ollama ps` to find which model(s) are currently resident in memory. */
//    Set<String> runningModelNames() {
//        String output = runOllamaCli("ps");
//        Set<String> names = new LinkedHashSet<>();
//        String[] lines = output.split("\n");
//        for (int i = 1; i < lines.length; i++) { // skip header row
//            String line = lines[i].trim();
//            if (line.isEmpty()) continue;
//            String[] cols = line.split("\\s{2,}");
//            if (cols.length > 0 && !cols[0].isBlank()) {
//                names.add(cols[0].trim());
//            }
//        }
//        return names;
//    }
//
//    /** Parses the whitespace-aligned columns of `ollama list`:  NAME  ID  SIZE  MODIFIED (possibly multi-word). */
//    private List<OllamaModelInfo> parseListOutput(String output) {
//        List<OllamaModelInfo> models = new ArrayList<>();
//        String[] lines = output.split("\n");
//        if (lines.length == 0) return models;
//
//        for (int i = 1; i < lines.length; i++) { // skip header row (NAME  ID  SIZE  MODIFIED)
//            String line = lines[i];
//            if (line == null || line.trim().isEmpty()) continue;
//
//            // columns are separated by 2+ spaces in ollama's CLI table output
//            String[] cols = line.trim().split("\\s{2,}");
//            if (cols.length < 3) continue;
//
//            OllamaModelInfo info = new OllamaModelInfo();
//            info.name = cols[0].trim();
//            info.id = cols.length > 1 ? cols[1].trim() : "";
//            info.size = cols.length > 2 ? cols[2].trim() : "";
//            // everything after SIZE is the "modified" column (e.g. "3 weeks ago"), rejoin in case it had extra spaces
//            info.modified = cols.length > 3
//                    ? String.join(" ", Arrays.copyOfRange(cols, 3, cols.length)).trim()
//                    : "";
//            models.add(info);
//        }
//        return models;
//    }
//
//    private String runOllamaCli(String... args) {
//        List<String> command = new ArrayList<>();
//        command.add("ollama");
//        command.addAll(Arrays.asList(args));
//
//        try {
//            ProcessBuilder pb = new ProcessBuilder(command);
//            pb.redirectErrorStream(true);
//            Process process = pb.start();
//
//            String output;
//            try (BufferedReader reader = new BufferedReader(
//                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
//                StringBuilder sb = new StringBuilder();
//                String line;
//                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
//                output = sb.toString();
//            }
//
//            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
//            if (!finished) {
//                process.destroyForcibly();
//                throw new RuntimeException("`ollama " + String.join(" ", args) + "` timed out after 30s");
//            }
//            if (process.exitValue() != 0) {
//                throw new RuntimeException("`ollama " + String.join(" ", args) + "` failed:\n" + output);
//            }
//            return output;
//        } catch (IOException e) {
//            throw new RuntimeException(
//                    "Could not run the 'ollama' CLI. Is Ollama installed and on PATH? " + e.getMessage(), e);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            throw new RuntimeException("Interrupted while running 'ollama " + String.join(" ", args) + "'", e);
//        }
//    }
//}
//
//// =====================================================================================
//// ===== PLANNER SERVICE: turns a user prompt into a PlanResponse =====================
//// =====================================================================================
//
//class PlannerService {
//
//    private final OllamaClient ollamaClient;
//    private final ObjectMapper mapper = new ObjectMapper();
//
//    PlannerService(OllamaClient ollamaClient) {
//        this.ollamaClient = ollamaClient;
//    }
//
//    private static final String SCHEMA_INSTRUCTIONS = """
//            You are a coding planner agent. Given a user's coding request, break it into an ordered
//            sequence of small, independently compilable and testable Java steps.
//
//            You MUST respond with ONLY valid JSON, no markdown fences, no commentary, matching EXACTLY
//            this schema:
//
//            {
//              "goal": "<one line summary of the overall task>",
//              "steps": [
//                {
//                  "stepNumber": 1,
//                  "description": "<what this step implements>",
//                  "generatedCode": "<COMPLETE compilable Java source, single public class, package-less, with a main method if it needs to be runnable standalone>",
//                  "className": "<the public class name used in generatedCode, must match exactly>",
//                  "testCode": "<COMPLETE compilable Java source, single public class with a main method, that exercises generatedCode's class and prints a line starting with RESULT: followed by PASS or FAIL and prints relevant output>",
//                  "testClassName": "<the public class name used in testCode>",
//                  "expectedResult": "RESULT:PASS",
//                  "status": "PENDING"
//                }
//              ],
//              "end": "END"
//            }
//
//            Rules:
//            - Each step's generatedCode must be FULLY self-contained (no references to other steps' classes,
//              since each step is compiled and run in isolation). If later steps build on earlier ones,
//              re-include the needed code (e.g. copy the class in, or grow it cumulatively) so every single
//              step compiles and runs on its own.
//            - generatedCode and testCode must each contain exactly one public class matching className/testClassName.
//            - testCode must print a line exactly starting with "RESULT:PASS" on success or "RESULT:FAIL" on failure,
//              and expectedResult must always be the literal string "RESULT:PASS".
//            - Do not use any third-party libraries, only java.* / javax.* standard library, since there is no
//              build system available to fetch dependencies.
//            - Keep each step small enough to reason about and verify on its own.
//            - Order steps so later ones depend only on concepts already established by earlier ones.
//            - Always end the JSON with "end": "END".
//            - Output raw JSON only. No ```json fences. No prose before or after.
//            """;
//
//    PlanResponse createPlan(String userPrompt) {
//        String fullPrompt = SCHEMA_INSTRUCTIONS + "\n\nUser request:\n" + userPrompt;
//        String raw = ollamaClient.generate(fullPrompt, true);
//        return parsePlan(raw);
//    }
//
//    /** Asks the model to fix one step's code given an exception, returns the corrected PlanStep fields. */
//    PlanStep fixStep(PlanStep failingStep, String errorDetails) {
//        String fixPrompt = """
//                The following Java code for a planner-agent step failed to compile or run, or failed its test.
//
//                Step description: %s
//
//                generatedCode:
//                %s
//
//                testCode:
//                %s
//
//                Error / failure details:
//                %s
//
//                Respond with ONLY valid JSON matching EXACTLY this schema (same as before), with corrected
//                generatedCode and testCode that fixes the problem. Keep className/testClassName the same
//                unless the error requires renaming. Keep expectedResult as "RESULT:PASS".
//
//                {
//                  "stepNumber": %d,
//                  "description": "%s",
//                  "generatedCode": "<fixed complete Java source>",
//                  "className": "%s",
//                  "testCode": "<fixed complete Java source>",
//                  "testClassName": "%s",
//                  "expectedResult": "RESULT:PASS",
//                  "status": "PENDING"
//                }
//
//                Output raw JSON only. No markdown fences. No prose.
//                """.formatted(
//                failingStep.description,
//                nullToEmpty(failingStep.generatedCode),
//                nullToEmpty(failingStep.testCode),
//                errorDetails,
//                failingStep.stepNumber,
//                escapeForPromptDisplay(failingStep.description),
//                nullToEmpty(failingStep.className),
//                nullToEmpty(failingStep.testClassName)
//        );
//
//        String raw = ollamaClient.generate(fixPrompt, true);
//        try {
//            return mapper.readValue(extractJson(raw), PlanStep.class);
//        } catch (Exception e) {
//            throw new RuntimeException("Could not parse fix-step JSON from model. Raw output:\n" + raw, e);
//        }
//    }
//
//    private PlanResponse parsePlan(String raw) {
//        try {
//            return mapper.readValue(extractJson(raw), PlanResponse.class);
//        } catch (Exception e) {
//            throw new RuntimeException("Could not parse plan JSON from model. Raw output:\n" + raw, e);
//        }
//    }
//
//    /** Strips accidental markdown fences / leading-trailing prose some small models still add despite instructions. */
//    private String extractJson(String raw) {
//        if (raw == null) throw new IllegalStateException("Null response from model");
//        String trimmed = raw.trim();
//        Pattern fence = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.MULTILINE);
//        Matcher m = fence.matcher(trimmed);
//        if (m.find()) {
//            return m.group(1).trim();
//        }
//        int firstBrace = trimmed.indexOf('{');
//        int lastBrace = trimmed.lastIndexOf('}');
//        if (firstBrace >= 0 && lastBrace > firstBrace) {
//            return trimmed.substring(firstBrace, lastBrace + 1);
//        }
//        return trimmed;
//    }
//
//    private String nullToEmpty(String s) { return s == null ? "" : s; }
//    private String escapeForPromptDisplay(String s) { return s == null ? "" : s.replace("\"", "'"); }
//}
//
//// =====================================================================================
//// ===== IN-MEMORY JAVA COMPILER + ISOLATED RUNNER =====================================
//// =====================================================================================
//
//class CompileResult {
//    boolean success;
//    String diagnostics;
//    Path classOutputDir;
//}
//
//class RunResult {
//    boolean success;
//    int exitCode;
//    String stdout;
//    String stderr;
//    boolean timedOut;
//}
//
//class JavaCodeRunner {
//
//    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
//
//    JavaCodeRunner() {
//        if (compiler == null) {
//            throw new IllegalStateException(
//                    "No system Java compiler found. Run this app with a full JDK (8-25), not a JRE, " +
//                            "as the project SDK in IntelliJ.");
//        }
//    }
//
//    /** Writes source to <workDir>/<className>.java, compiles it in-memory, returns the output dir with .class files. */
//    CompileResult compile(String sourceCode, String className, Path workDir) {
//        CompileResult result = new CompileResult();
//        try {
//            Files.createDirectories(workDir);
//            Path sourceFile = workDir.resolve(className + ".java");
//            Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);
//
//            StringWriter diagnosticsWriter = new StringWriter();
//            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
//
//            try (StandardJavaFileManager fileManager =
//                         compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
//                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(workDir.toFile()));
//                Iterable<? extends JavaFileObject> units =
//                        fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));
//
//                JavaCompiler.CompilationTask task = compiler.getTask(
//                        diagnosticsWriter, fileManager, diagnostics, null, null, units);
//
//                boolean ok = task.call();
//                result.success = ok;
//                result.classOutputDir = workDir;
//
//                StringBuilder diagText = new StringBuilder();
//                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
//                    diagText.append(d.toString()).append("\n");
//                }
//                diagText.append(diagnosticsWriter);
//                result.diagnostics = diagText.toString();
//            }
//        } catch (Exception e) {
//            result.success = false;
//            result.diagnostics = "Compilation infrastructure error: " + e;
//        }
//        return result;
//    }
//
//    /** Runs the compiled class's main() in a separate JVM process so it can't crash/hang the agent itself. */
//    RunResult run(Path classOutputDir, String className) {
//        RunResult result = new RunResult();
//        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
//        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", classOutputDir.toAbsolutePath().toString(), className);
//        pb.redirectErrorStream(false);
//        try {
//            Process process = pb.start();
//            String stdout = readStream(process.getInputStream());
//            String stderr = readStream(process.getErrorStream());
//            boolean finished = process.waitFor(AgentConfig.PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
//            if (!finished) {
//                process.destroyForcibly();
//                result.timedOut = true;
//                result.success = false;
//                result.stderr = "Process timed out after " + AgentConfig.PROCESS_TIMEOUT_SECONDS + "s";
//                return result;
//            }
//            result.exitCode = process.exitValue();
//            result.stdout = stdout;
//            result.stderr = stderr;
//            result.success = result.exitCode == 0;
//        } catch (Exception e) {
//            result.success = false;
//            result.stderr = "Process execution error: " + e;
//        }
//        return result;
//    }
//
//    private String readStream(InputStream in) throws IOException {
//        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
//            StringBuilder sb = new StringBuilder();
//            String line;
//            while ((line = reader.readLine()) != null) {
//                sb.append(line).append("\n");
//            }
//            return sb.toString();
//        }
//    }
//}
//
//// =====================================================================================
//// ===== PROGRESS EVENTS: pushed live over SSE so the UI isn't a static screen ========
//// =====================================================================================
//
//class ProgressEvent {
//    public String type;       // PLANNING | PLAN_READY | STEP_START | STEP_PHASE | STEP_RETRY | STEP_RESULT | DONE | ERROR
//    public Integer stepNumber;
//    public String stepDescription;
//    public String phase;      // e.g. "compiling", "running", "testing", "requesting fix"
//    public String message;    // short human-readable line for a live log
//    public String status;     // PENDING | RUNNING | PASSED | FAILED (mirrors PlanStep.status)
//    public Integer attempt;
//    public PlanResponse plan;          // present on PLAN_READY
//    public PlanStep step;              // present on STEP_RESULT (full step with code/output)
//    public ExecuteResponse finalResult; // present on DONE
//
//    static ProgressEvent of(String type, String message) {
//        ProgressEvent e = new ProgressEvent();
//        e.type = type;
//        e.message = message;
//        return e;
//    }
//}
//
//// =====================================================================================
//// ===== STEP EXECUTOR: drives compile -> run -> test -> retry-on-failure loop =========
//// =====================================================================================
//
//class StepExecutor {
//
//    private final JavaCodeRunner runner = new JavaCodeRunner();
//    private final PlannerService plannerService;
//    private final Path baseWorkDir;
//
//    StepExecutor(PlannerService plannerService) {
//        this.plannerService = plannerService;
//        try {
//            this.baseWorkDir = Files.createTempDirectory("planner-agent-");
//        } catch (IOException e) {
//            throw new RuntimeException("Could not create working directory", e);
//        }
//    }
//
//    /** Executes all steps in order; stops at first step that can't be made to pass within MAX_RETRIES. */
//    boolean executeAll(List<PlanStep> steps) {
//        return executeAll(steps, null);
//    }
//
//    /** Same as executeAll, but emits a ProgressEvent after every notable sub-step via onProgress (may be null). */
//    boolean executeAll(List<PlanStep> steps, java.util.function.Consumer<ProgressEvent> onProgress) {
//        for (PlanStep step : steps) {
//            boolean passed = executeStepWithRetries(step, onProgress);
//            step.status = passed ? "PASSED" : "FAILED";
//
//            ProgressEvent resultEvent = ProgressEvent.of("STEP_RESULT",
//                    "Step " + step.stepNumber + ": " + (passed ? "passed" : "failed") +
//                            (step.attempts > 1 ? " after " + step.attempts + " attempts" : ""));
//            resultEvent.stepNumber = step.stepNumber;
//            resultEvent.status = step.status;
//            resultEvent.attempt = step.attempts;
//            resultEvent.step = step;
//            emit(onProgress, resultEvent);
//
//            if (!passed) {
//                return false; // stop the pipeline: do not proceed to next step
//            }
//        }
//        return true;
//    }
//
//    private boolean executeStepWithRetries(PlanStep step, java.util.function.Consumer<ProgressEvent> onProgress) {
//        ProgressEvent startEvent = ProgressEvent.of("STEP_START",
//                "Step " + step.stepNumber + ": " + step.description);
//        startEvent.stepNumber = step.stepNumber;
//        startEvent.stepDescription = step.description;
//        startEvent.status = "RUNNING";
//        emit(onProgress, startEvent);
//
//        for (int attempt = 1; attempt <= AgentConfig.MAX_RETRIES_PER_STEP; attempt++) {
//            step.attempts = attempt;
//            try {
//                if (runOneAttempt(step, onProgress)) {
//                    return true;
//                }
//                // runOneAttempt returned false but didn't throw: test ran but mismatched expected result
//                String feedback = "generatedCode compiled/ran but test did not match expectedResult.\n" +
//                        "compileOutput: " + step.compileOutput + "\n" +
//                        "runOutput: " + step.runOutput + "\n" +
//                        "testRunOutput: " + step.testRunOutput + "\n" +
//                        "expectedResult: " + step.expectedResult;
//                step.lastError = feedback;
//                phaseEvent(onProgress, step, "test-mismatch",
//                        "Step " + step.stepNumber + ": test ran but output didn't match expected result");
//            } catch (Exception e) {
//                step.lastError = stackTraceToString(e);
//                phaseEvent(onProgress, step, "error",
//                        "Step " + step.stepNumber + ": " + firstLine(e.getMessage()));
//            }
//
//            if (attempt < AgentConfig.MAX_RETRIES_PER_STEP) {
//                // feed the exception/failure back to the LLM and patch the step with its fix
//                ProgressEvent retryEvent = ProgressEvent.of("STEP_RETRY",
//                        "Step " + step.stepNumber + ": asking the model to fix attempt " + attempt + "'s error\u2026");
//                retryEvent.stepNumber = step.stepNumber;
//                retryEvent.attempt = attempt;
//                retryEvent.status = "RUNNING";
//                emit(onProgress, retryEvent);
//
//                try {
//                    PlanStep fixed = plannerService.fixStep(step, step.lastError);
//                    step.generatedCode = fixed.generatedCode;
//                    step.className = fixed.className;
//                    step.testCode = fixed.testCode;
//                    step.testClassName = fixed.testClassName;
//                    if (fixed.expectedResult != null && !fixed.expectedResult.isBlank()) {
//                        step.expectedResult = fixed.expectedResult;
//                    }
//                    phaseEvent(onProgress, step, "fix-received",
//                            "Step " + step.stepNumber + ": got a fix from the model, retrying\u2026");
//                } catch (Exception fixEx) {
//                    step.lastError += "\n\nAdditionally failed to get a fix from the model: " + fixEx.getMessage();
//                    phaseEvent(onProgress, step, "fix-failed",
//                            "Step " + step.stepNumber + ": couldn't get a fix (" + firstLine(fixEx.getMessage()) + "), retrying same code\u2026");
//                    // keep retrying with the same code in case it was a transient Ollama call failure
//                }
//            }
//        }
//        return false;
//    }
//
//    /** One compile+run+test pass. Returns true only if test output matches expectedResult. Throws on infra errors. */
//    private boolean runOneAttempt(PlanStep step, java.util.function.Consumer<ProgressEvent> onProgress) throws Exception {
//        Path stepDir = baseWorkDir.resolve("step-" + step.stepNumber + "-attempt-" + step.attempts);
//
//        // 1. compile generatedCode
//        phaseEvent(onProgress, step, "compiling", "Step " + step.stepNumber + ": compiling generated code\u2026");
//        Path codeDir = stepDir.resolve("code");
//        CompileResult codeCompile = runner.compile(step.generatedCode, step.className, codeDir);
//        step.compileOutput = codeCompile.diagnostics;
//        if (!codeCompile.success) {
//            throw new RuntimeException("Compilation failed for step " + step.stepNumber + ":\n" + codeCompile.diagnostics);
//        }
//
//        // 2. run generatedCode's main(), if it has one producing standalone behavior worth capturing
//        phaseEvent(onProgress, step, "running", "Step " + step.stepNumber + ": running generated code\u2026");
//        RunResult codeRun = runner.run(codeDir, step.className);
//        step.runOutput = (codeRun.stdout == null ? "" : codeRun.stdout) +
//                (codeRun.stderr == null ? "" : codeRun.stderr);
//        if (codeRun.timedOut) {
//            throw new RuntimeException("generatedCode for step " + step.stepNumber + " timed out:\n" + step.runOutput);
//        }
//        if (!codeRun.success) {
//            throw new RuntimeException("generatedCode for step " + step.stepNumber +
//                    " exited with code " + codeRun.exitCode + ":\n" + step.runOutput);
//        }
//
//        // 3. compile testCode (copy generatedCode's class file alongside so the test can reference it,
//        //    in addition to re-declaring it if the model inlined everything into one file)
//        phaseEvent(onProgress, step, "compiling-test", "Step " + step.stepNumber + ": compiling test\u2026");
//        Path testDir = stepDir.resolve("test");
//        Files.createDirectories(testDir);
//        copyClassFiles(codeDir, testDir);
//        CompileResult testCompile = runner.compile(step.testCode, step.testClassName, testDir);
//        step.testRunOutput = testCompile.diagnostics;
//        if (!testCompile.success) {
//            throw new RuntimeException("Test compilation failed for step " + step.stepNumber + ":\n" + testCompile.diagnostics);
//        }
//
//        // 4. run testCode
//        phaseEvent(onProgress, step, "testing", "Step " + step.stepNumber + ": running test\u2026");
//        RunResult testRun = runner.run(testDir, step.testClassName);
//        String testOutput = (testRun.stdout == null ? "" : testRun.stdout) +
//                (testRun.stderr == null ? "" : testRun.stderr);
//        step.testRunOutput = testOutput;
//        if (testRun.timedOut) {
//            throw new RuntimeException("testCode for step " + step.stepNumber + " timed out:\n" + testOutput);
//        }
//
//        String expected = step.expectedResult == null ? "RESULT:PASS" : step.expectedResult;
//        boolean matched = testOutput.contains(expected);
//        if (matched) {
//            phaseEvent(onProgress, step, "test-passed", "Step " + step.stepNumber + ": test passed \u2713");
//        }
//        return matched;
//    }
//
//    private void phaseEvent(java.util.function.Consumer<ProgressEvent> onProgress, PlanStep step, String phase, String message) {
//        ProgressEvent e = ProgressEvent.of("STEP_PHASE", message);
//        e.stepNumber = step.stepNumber;
//        e.phase = phase;
//        e.attempt = step.attempts;
//        e.status = "RUNNING";
//        emit(onProgress, e);
//    }
//
//    private void emit(java.util.function.Consumer<ProgressEvent> onProgress, ProgressEvent event) {
//        if (onProgress != null) {
//            try {
//                onProgress.accept(event);
//            } catch (Exception ignored) {
//                // a broken/closed client stream shouldn't abort the actual pipeline run
//            }
//        }
//    }
//
//    private String firstLine(String s) {
//        if (s == null) return "unknown error";
//        int nl = s.indexOf('\n');
//        return nl > 0 ? s.substring(0, nl) : s;
//    }
//
//    private void copyClassFiles(Path from, Path to) throws IOException {
//        if (!Files.exists(from)) return;
//        try (DirectoryStream<Path> stream = Files.newDirectoryStream(from, "*.class")) {
//            for (Path p : stream) {
//                Files.copy(p, to.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
//            }
//        }
//    }
//
//    private String stackTraceToString(Exception e) {
//        StringWriter sw = new StringWriter();
//        e.printStackTrace(new PrintWriter(sw));
//        return sw.toString();
//    }
//}
//
//// =====================================================================================
//// ===== REST CONTROLLER ===============================================================
//// =====================================================================================
//
//@RestController
//@RequestMapping("/api/plan")
//@CrossOrigin(origins = "*") // UI is a standalone HTML file (often opened via file://), so allow any origin
//class PlannerController {
//
//    private final OllamaClient ollamaClient = new OllamaClient();
//    private final PlannerService plannerService = new PlannerService(ollamaClient);
//
//    @PostMapping("/execute")
//    public ExecuteResponse execute(@RequestBody ExecuteRequest request) {
//        ExecuteResponse response = new ExecuteResponse();
//        try {
//            PlanResponse plan = plannerService.createPlan(request.prompt);
//            response.goal = plan.goal;
//            response.steps = plan.steps;
//
//            StepExecutor executor = new StepExecutor(plannerService);
//            boolean allPassed = executor.executeAll(plan.steps);
//
//            response.overallSuccess = allPassed;
//            response.message = allPassed
//                    ? "All " + plan.steps.size() + " steps completed and passed."
//                    : "Pipeline stopped: a step failed after " + AgentConfig.MAX_RETRIES_PER_STEP + " attempts.";
//        } catch (Exception e) {
//            response.overallSuccess = false;
//            response.message = "Planning/execution error: " + e.getMessage();
//        }
//        return response;
//    }
//
//    /** Lightweight plan-only endpoint for debugging the schema without running the full execute loop. */
//    @PostMapping("/plan-only")
//    public PlanResponse planOnly(@RequestBody ExecuteRequest request) {
//        return plannerService.createPlan(request.prompt);
//    }
//
//    /**
//     * GET /api/plan/execute-stream?prompt=... — same pipeline as /execute, but pushes a live
//     * Server-Sent Events stream of ProgressEvent objects as planning and each step happen, so the UI
//     * can show what's going on instead of a single multi-minute blocking wait. A GET (not POST) is used
//     * here because EventSource, the standard browser SSE client, can only issue GET requests.
//     * Ends with one "DONE" event carrying the same ExecuteResponse /execute would have returned,
//     * or an "ERROR" event if planning/execution blew up before producing a plan at all.
//     */
//    @GetMapping(value = "/execute-stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
//    public SseEmitter executeStream(@RequestParam("prompt") String prompt) {
//        SseEmitter emitter = new SseEmitter(0L); // 0 = no timeout; local models can run long
//
//        Thread worker = new Thread(() -> {
//            ExecuteResponse response = new ExecuteResponse();
//            try {
//                sendEvent(emitter, ProgressEvent.of("PLANNING", "Asking the model to plan this out\u2026"));
//
//                PlanResponse plan = plannerService.createPlan(prompt);
//                response.goal = plan.goal;
//                response.steps = plan.steps;
//
//                ProgressEvent planReady = ProgressEvent.of("PLAN_READY",
//                        "Plan ready: " + plan.steps.size() + " step(s).");
//                planReady.plan = plan;
//                sendEvent(emitter, planReady);
//
//                StepExecutor executor = new StepExecutor(plannerService);
//                boolean allPassed = executor.executeAll(plan.steps, event -> sendEvent(emitter, event));
//
//                response.overallSuccess = allPassed;
//                response.message = allPassed
//                        ? "All " + plan.steps.size() + " steps completed and passed."
//                        : "Pipeline stopped: a step failed after " + AgentConfig.MAX_RETRIES_PER_STEP + " attempts.";
//
//                ProgressEvent doneEvent = ProgressEvent.of("DONE", response.message);
//                doneEvent.finalResult = response;
//                sendEvent(emitter, doneEvent);
//                emitter.complete();
//            } catch (Exception e) {
//                response.overallSuccess = false;
//                response.message = "Planning/execution error: " + e.getMessage();
//                ProgressEvent errorEvent = ProgressEvent.of("ERROR", response.message);
//                errorEvent.finalResult = response;
//                sendEvent(emitter, errorEvent);
//                emitter.complete();
//            }
//        }, "planner-sse-worker");
//        worker.setDaemon(true);
//        worker.start();
//
//        emitter.onTimeout(emitter::complete);
//        emitter.onError(ex -> emitter.complete());
//        return emitter;
//    }
//
//    private void sendEvent(SseEmitter emitter, ProgressEvent event) {
//        try {
//            emitter.send(SseEmitter.event().name("progress").data(event, org.springframework.http.MediaType.APPLICATION_JSON));
//        } catch (Exception e) {
//            // client likely disconnected; nothing more to do here, the worker thread will keep running
//            // the actual pipeline to completion regardless (events just won't be delivered further)
//        }
//    }
//}
//
//// =====================================================================================
//// ===== MODEL MANAGEMENT CONTROLLER: list / select / load / unload local models ======
//// =====================================================================================
//
//class ModelActionRequest {
//    public String modelName;
//}
//
//@RestController
//@RequestMapping("/api/models")
//@CrossOrigin(origins = "*")
//class ModelController {
//
//    private final OllamaModelService modelService = new OllamaModelService();
//    private final OllamaClient ollamaClient = new OllamaClient();
//
//    /** GET /api/models — lists every locally pulled model (parsed from `ollama list`), with load state and which one is active. */
//    @GetMapping
//    public Map<String, Object> listModels() {
//        List<OllamaModelInfo> models = modelService.listModels();
//        Set<String> running = safeRunningModels();
//
//        List<Map<String, Object>> enriched = new ArrayList<>();
//        for (OllamaModelInfo m : models) {
//            Map<String, Object> row = new LinkedHashMap<>();
//            row.put("name", m.name);
//            row.put("id", m.id);
//            row.put("size", m.size);
//            row.put("modified", m.modified);
//            row.put("loaded", running.contains(m.name));
//            row.put("active", m.name.equals(AgentConfig.getActiveModel()));
//            enriched.add(row);
//        }
//
//        Map<String, Object> result = new LinkedHashMap<>();
//        result.put("models", enriched);
//        result.put("activeModel", AgentConfig.getActiveModel());
//        return result;
//    }
//
//    /** GET /api/models/running — just the names currently resident in memory, per `ollama ps`. */
//    @GetMapping("/running")
//    public Map<String, Object> running() {
//        Map<String, Object> result = new LinkedHashMap<>();
//        result.put("running", safeRunningModels());
//        return result;
//    }
//
//    /**
//     * POST /api/models/select { "modelName": "..." } — marks a model as the active one used for all
//     * future planning/fix calls, AND loads it into memory immediately (keep_alive: -1, i.e. stays
//     * resident until explicitly unloaded) so the first real prompt isn't slowed down by a cold load.
//     */
//    @PostMapping("/select")
//    public ModelActionResponse select(@RequestBody ModelActionRequest request) {
//        ModelActionResponse response = new ModelActionResponse();
//        if (request.modelName == null || request.modelName.isBlank()) {
//            response.success = false;
//            response.message = "modelName is required";
//            response.activeModel = AgentConfig.getActiveModel();
//            return response;
//        }
//        try {
//            ollamaClient.setModelKeepAlive(request.modelName, -1L);
//            AgentConfig.setActiveModel(request.modelName);
//            response.success = true;
//            response.message = "Loaded and selected '" + request.modelName + "' as the active model.";
//            response.activeModel = AgentConfig.getActiveModel();
//        } catch (Exception e) {
//            response.success = false;
//            response.message = "Failed to load model: " + e.getMessage();
//            response.activeModel = AgentConfig.getActiveModel();
//        }
//        return response;
//    }
//
//    /** POST /api/models/unload { "modelName": "..." } — evicts the model from memory (keep_alive: 0). */
//    @PostMapping("/unload")
//    public ModelActionResponse unload(@RequestBody ModelActionRequest request) {
//        ModelActionResponse response = new ModelActionResponse();
//        if (request.modelName == null || request.modelName.isBlank()) {
//            response.success = false;
//            response.message = "modelName is required";
//            response.activeModel = AgentConfig.getActiveModel();
//            return response;
//        }
//        try {
//            ollamaClient.setModelKeepAlive(request.modelName, 0L);
//            response.success = true;
//            response.message = "Unloaded '" + request.modelName + "' from memory.";
//            response.activeModel = AgentConfig.getActiveModel();
//        } catch (Exception e) {
//            response.success = false;
//            response.message = "Failed to unload model: " + e.getMessage();
//            response.activeModel = AgentConfig.getActiveModel();
//        }
//        return response;
//    }
//
//    private Set<String> safeRunningModels() {
//        try {
//            return modelService.runningModelNames();
//        } catch (Exception e) {
//            return Collections.emptySet(); // don't fail the whole listing just because `ollama ps` hiccuped
//        }
//    }
//}