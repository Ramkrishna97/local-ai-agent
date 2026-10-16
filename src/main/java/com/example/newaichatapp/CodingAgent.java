//package com.example.newaichatapp;
//
//import org.springframework.http.*;
//import org.springframework.web.bind.annotation.*;
//import tools.jackson.databind.*;
//import tools.jackson.databind.node.ObjectNode;
//
//import java.io.IOException;
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.net.http.HttpRequest;
//import java.net.http.HttpResponse;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.time.Duration;
//import java.time.LocalDateTime;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//import java.util.stream.Collectors;
//
//import java.io.IOException;
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.net.http.HttpRequest;
//import java.net.http.HttpResponse;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.time.Duration;
//import java.time.LocalDateTime;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//import java.util.stream.Collectors;
//
//@RestController
//@RequestMapping("/api/coding-agent")
//public class CodingAgent {
//
//    // =========================================================
//    // CONFIGURATION
//    // =========================================================
//
//    private final ObjectMapper mapper = new ObjectMapper();
//
//    private final String ollamaUrl =
//            System.getenv().getOrDefault(
//                    "OLLAMA_URL",
//                    "http://localhost:11434"
//            );
//
//    private final String model =
//            System.getenv().getOrDefault(
//                    "OLLAMA_MODEL",
//                    "nemotron-3-nano:4b"
//            );
//
//    private final int maxRetries = 3;
//
//    private final Path rootWorkspace =
//            Paths.get("agent-workspace")
//                    .toAbsolutePath()
//                    .normalize();
//
//    private final HttpClient httpClient =
//            HttpClient.newBuilder()
//                    .connectTimeout(Duration.ofSeconds(30))
//                    .build();
//
//    private final ExecutorService executor =
//            Executors.newSingleThreadExecutor();
//
//    private final Map<String, Job> jobs =
//            new ConcurrentHashMap<>();
//
//    public CodingAgent() throws IOException {
//        Files.createDirectories(rootWorkspace);
//    }
//
//    // =========================================================
//    // MODEL CLASSES
//    // =========================================================
//
//    public static class CodingRequest {
//
//        public String requirement;
//
//        public CodingRequest() {
//        }
//
//        public CodingRequest(String requirement) {
//            this.requirement = requirement;
//        }
//    }
//
//    public static class Job {
//
//        public String id;
//        public String requirement;
//        public String status = "QUEUED";
//        public String currentStep;
//        public String error;
//        public String workspace;
//
//        public JsonNode plan;
//
//        public List<StepResult> results =
//                new CopyOnWriteArrayList<>();
//
//        public List<String> logs =
//                new CopyOnWriteArrayList<>();
//
//        public LocalDateTime createdAt = LocalDateTime.now();
//    }
//
//    public static class StepResult {
//
//        public String stepKey;
//        public String description;
//        public String status = "PENDING";
//
//        public boolean pass;
//
//        public int attempts;
//
//        public String generatedCodeSummary;
//        public String expectedTestResults;
//        public String actualTestResults;
//        public String error;
//
//        public StepResult(String stepKey) {
//            this.stepKey = stepKey;
//        }
//    }
//
//    public record CommandResult(
//            int exitCode,
//            String output
//    ) {
//    }
//
//    // =========================================================
//    // REST ENDPOINTS
//    // =========================================================
//
//    @PostMapping("/start")
//    public ResponseEntity<?> start(
//            @RequestBody CodingRequest request) {
//
//        if (request == null ||
//                request.requirement == null ||
//                request.requirement.isBlank()) {
//
//            return ResponseEntity.badRequest().body(
//                    Map.of("error", "Requirement cannot be empty")
//            );
//        }
//
//        Job job = new Job();
//
//        job.id = UUID.randomUUID().toString();
//        job.requirement = request.requirement;
//
//        jobs.put(job.id, job);
//
//        executor.submit(() -> executeJob(job));
//
//        return ResponseEntity.accepted().body(
//                Map.of(
//                        "jobId", job.id,
//                        "status", job.status,
//                        "message", "Coding agent started"
//                )
//        );
//    }
//
//    @GetMapping("/status/{jobId}")
//    public ResponseEntity<?> status(
//            @PathVariable String jobId) {
//
//        Job job = jobs.get(jobId);
//
//        if (job == null) {
//            return ResponseEntity.notFound().build();
//        }
//
//        return ResponseEntity.ok(job);
//    }
//
//    // =========================================================
//    // MAIN ORCHESTRATOR
//    // =========================================================
//
//    private void executeJob(Job job) {
//
//        try {
//
//            job.status = "PLANNING";
//
//            log(job, "Generating planner JSON");
//
//            JsonNode plan = generatePlan(job.requirement);
//
//            validatePlan(plan);
//
//            job.plan = plan;
//
//            Path projectDir =
//                    rootWorkspace.resolve(job.id).normalize();
//
//            if (!projectDir.startsWith(rootWorkspace)) {
//                throw new SecurityException(
//                        "Invalid workspace path"
//                );
//            }
//
//            Files.createDirectories(projectDir);
//
//            job.workspace = projectDir.toString();
//
//            /*
//             * Read step1, step2, step3 ... sequentially.
//             */
//            List<String> stepKeys = getStepKeys(plan);
//
//            job.status = "EXECUTING";
//
//            for (String stepKey : stepKeys) {
//
//                JsonNode step = plan.get(stepKey);
//
//                job.currentStep = stepKey;
//
//                StepResult result = new StepResult(stepKey);
//
//                result.description =
//                        step.path("description").asText();
//
//                job.results.add(result);
//
//                log(
//                        job,
//                        "Starting " + stepKey +
//                                ": " + result.description
//                );
//
//                executeStep(
//                        job,
//                        projectDir,
//                        stepKey,
//                        step,
//                        result
//                );
//
//                /*
//                 * Hard gate: never continue after failure.
//                 */
//                if (!result.pass) {
//
//                    job.status = "FAILED";
//
//                    throw new IllegalStateException(
//                            stepKey + " did not pass verification"
//                    );
//                }
//
//                log(job, stepKey + " PASSED");
//            }
//
//            /*
//             * Final full-project verification.
//             */
//            log(job, "Running final clean test");
//
//            CommandResult finalResult =
//                    runMaven(projectDir, "clean", "test");
//
//            if (finalResult.exitCode() != 0) {
//
//                throw new IllegalStateException(
//                        "Final verification failed:\n" +
//                                finalResult.output()
//                );
//            }
//
//            job.status = "COMPLETED";
//
//            log(job, "All steps and final tests passed");
//
//        } catch (Exception e) {
//
//            job.status = "FAILED";
//
//            job.error = e.getMessage();
//
//            log(job, "PROJECT FAILED: " + e.getMessage());
//        }
//    }
//
//    // =========================================================
//    // FIXED PLANNER SCHEMA
//    // =========================================================
//
//    private JsonNode generatePlan(String requirement)
//            throws Exception {
//
//        String prompt = """
//                You are a software implementation planner.
//
//                Convert the client's request into a sequence
//                of small, verifiable coding steps.
//
//                You MUST use the exact JSON structure below.
//
//                {
//                  "projectName": "string",
//                  "projectType": "java-maven",
//                  "step1": {
//                    "description": "string",
//                    "generateCode": {
//                      "instructions": "string",
//                      "expectedFiles": ["string"]
//                    },
//                    "generateExpectedTest": {
//                      "instructions": "string",
//                      "expectedResults": ["string"]
//                    },
//                    "pass": {
//                      "condition": "string"
//                    }
//                  },
//                  "step2": {
//                    "description": "string",
//                    "generateCode": {
//                      "instructions": "string",
//                      "expectedFiles": ["string"]
//                    },
//                    "generateExpectedTest": {
//                      "instructions": "string",
//                      "expectedResults": ["string"]
//                    },
//                    "pass": {
//                      "condition": "string"
//                    }
//                  }
//                }
//
//                RULES:
//
//                1. Every step MUST contain all four keys:
//                   description, generateCode,
//                   generateExpectedTest, pass.
//
//                2. Use sequential step keys:
//                   step1, step2, step3, step4, and so on.
//
//                3. Do not use arrays for the steps themselves.
//
//                4. Generate between 3 and 10 steps.
//
//                5. Every expectedFiles entry must be a relative path.
//
//                6. Expected test results must describe observable,
//                   verifiable behavior.
//
//                7. Include project setup before implementation.
//
//                8. Include meaningful tests.
//
//                9. The last step must verify the completed project.
//
//                10. Return ONLY JSON. No Markdown fences.
//
//                CLIENT REQUEST:
//                """ + requirement;
//
//        String response = callOllama(prompt);
//
//        JsonNode plan = parseJson(response);
//
//        if (!plan.isObject()) {
//            throw new IllegalArgumentException(
//                    "Planner response must be a JSON object"
//            );
//        }
//
//        return plan;
//    }
//
//    private void validatePlan(JsonNode plan) {
//
//        if (plan.path("projectName").asText().isBlank()) {
//
//            throw new IllegalArgumentException(
//                    "Missing projectName"
//            );
//        }
//
//        if (!"java-maven".equals(
//                plan.path("projectType").asText())) {
//
//            throw new IllegalArgumentException(
//                    "Only java-maven projects are supported"
//            );
//        }
//
//        List<String> keys = getStepKeys(plan);
//
//        if (keys.isEmpty()) {
//
//            throw new IllegalArgumentException(
//                    "Planner returned no steps"
//            );
//        }
//
//        if (keys.size() > 10) {
//
//            throw new IllegalArgumentException(
//                    "Maximum 10 steps supported"
//            );
//        }
//
//        for (String key : keys) {
//
//            JsonNode step = plan.get(key);
//
//            if (step.path("description").asText().isBlank()) {
//                throw new IllegalArgumentException(
//                        key + " has no description"
//                );
//            }
//
//            for (String requiredKey : List.of(
//                    "generateCode",
//                    "generateExpectedTest",
//                    "pass"
//            )) {
//
//                if (!step.has(requiredKey) ||
//                        !step.get(requiredKey).isObject()) {
//
//                    throw new IllegalArgumentException(
//                            key + " missing object: " + requiredKey
//                    );
//                }
//            }
//
//            if (!step.path("generateCode")
//                    .path("expectedFiles").isArray()) {
//
//                throw new IllegalArgumentException(
//                        key + " must define expectedFiles"
//                );
//            }
//
//            if (!step.path("generateExpectedTest")
//                    .path("expectedResults").isArray()) {
//
//                throw new IllegalArgumentException(
//                        key + " must define expectedResults"
//                );
//            }
//        }
//    }
//
//    /*
//     * Only accept sequential keys: step1, step2, step3 ...
//     * Reject missing or non-sequential numbering.
//     */
//    private List<String> getStepKeys(JsonNode plan) {
//        Map<Integer, String> numbered = new TreeMap<>();
//        Pattern pattern = Pattern.compile("^step(\\d+)$");
//
//        // Use getFieldNames() for Jackson 1.x (org.codehaus.jackson)
//        Iterator<String> fields = plan.getFieldNames();
//
//        while (fields.hasNext()) {
//            String key = fields.next();
//            Matcher matcher = pattern.matcher(key);
//
//            if (matcher.matches()) {
//                int number;
//
//                try {
//                    number = Integer.parseInt(matcher.group(1));
//                } catch (NumberFormatException e) {
//                    throw new IllegalArgumentException(
//                            "Invalid step key: " + key
//                    );
//                }
//
//                if (number < 1 || numbered.containsKey(number)) {
//                    throw new IllegalArgumentException(
//                            "Invalid or duplicate step number: " + key
//                    );
//                }
//
//                numbered.put(number, key);
//            }
//        }
//
//        if (numbered.isEmpty()) {
//            throw new IllegalArgumentException(
//                    "Plan must contain at least one step (step1, step2, ...)"
//            );
//        }
//
//        List<String> stepKeys = new ArrayList<>(numbered.values());
//
//        for (int i = 0; i < stepKeys.size(); i++) {
//            String expectedKey = "step" + (i + 1);
//
//            if (!stepKeys.get(i).equals(expectedKey)) {
//                throw new IllegalArgumentException(
//                        "Missing step: " + expectedKey
//                );
//            }
//        }
//
//        return stepKeys;
//    }
//
//    // =========================================================
//    // STEP EXECUTION
//    // =========================================================
//
//    private void executeStep(
//            Job job,
//            Path projectDir,
//            String stepKey,
//            JsonNode step,
//            StepResult result
//    ) throws Exception {
//
//        String previousFailure = "";
//
//        for (int attempt = 1; attempt <= maxRetries; attempt++) {
//
//            result.attempts = attempt;
//
//            result.status = "GENERATING";
//
//            log(
//                    job,
//                    stepKey + " attempt " + attempt
//            );
//
//            String currentFiles = readProjectFiles(projectDir);
//
//            /*
//             * Generate implementation code and test code.
//             */
//            String prompt = """
//                    You are implementing one step of a Java project.
//
//                    CLIENT REQUIREMENT:
//                    %s
//
//                    CURRENT STEP:
//                    %s
//
//                    STEP DESCRIPTION:
//                    %s
//
//                    CODE GENERATION INSTRUCTIONS:
//                    %s
//
//                    EXPECTED FILES:
//                    %s
//
//                    TEST GENERATION INSTRUCTIONS:
//                    %s
//
//                    EXPECTED TEST RESULTS:
//                    %s
//
//                    PASS CONDITION:
//                    %s
//
//                    EXISTING PROJECT FILES:
//                    %s
//
//                    PREVIOUS FAILURE:
//                    %s
//
//                    RULES:
//                    - Generate only files needed for this step.
//                    - Include Java source and JUnit tests where applicable.
//                    - Return complete file contents.
//                    - Do not include shell commands.
//                    - Use paths relative to the project root.
//                    - Do not claim tests passed.
//                    - Do not overwrite files unrelated to the step.
//                    - Keep the code compatible with Java 17.
//                    - Include pom.xml during project setup.
//
//                    Return ONLY JSON:
//
//                    {
//                      "changes": [
//                        {
//                          "path": "relative/path",
//                          "content": "complete file content"
//                        }
//                      ]
//                    }
//                    """.formatted(
//                    job.requirement,
//                    stepKey,
//                    step.path("description").asText(),
//                    step.path("generateCode")
//                            .path("instructions").asText(),
//                    step.path("generateCode")
//                            .path("expectedFiles").toString(),
//                    step.path("generateExpectedTest")
//                            .path("instructions").asText(),
//                    step.path("generateExpectedTest")
//                            .path("expectedResults").toString(),
//                    step.path("pass")
//                            .path("condition").asText(),
//                    currentFiles,
//                    previousFailure
//            );
//
//            JsonNode generated =
//                    parseJson(callOllama(prompt));
//
//            JsonNode changes = generated.get("changes");
//
//            if (changes == null ||
//                    !changes.isArray() ||
//                    changes.isEmpty()) {
//
//                previousFailure =
//                        "No valid file changes were generated";
//
//                continue;
//            }
//
//            applyChanges(projectDir, changes);
//
//            result.status = "VERIFYING";
//
//            /*
//             * First, compile.
//             */
//            CommandResult compile =
//                    runMaven(projectDir, "compile");
//
//            if (compile.exitCode() != 0) {
//
//                previousFailure =
//                        "COMPILATION FAILED:\n" +
//                                compile.output();
//
//                result.error = previousFailure;
//
//                log(job, previousFailure);
//
//                continue;
//            }
//
//            /*
//             * Then execute the tests.
//             */
//            CommandResult tests =
//                    runMaven(projectDir, "test");
//
//            result.actualTestResults = tests.output();
//
//            if (tests.exitCode() != 0) {
//
//                previousFailure =
//                        "TESTS FAILED:\n" +
//                                tests.output();
//
//                result.error = previousFailure;
//
//                log(job, previousFailure);
//
//                continue;
//            }
//
//            /*
//             * Verify expected files exist.
//             */
//            String fileFailure =
//                    verifyExpectedFiles(
//                            projectDir,
//                            step.path("generateCode")
//                                    .path("expectedFiles")
//                    );
//
//            if (fileFailure != null) {
//
//                previousFailure = fileFailure;
//
//                result.error = fileFailure;
//
//                continue;
//            }
//
//            /*
//             * A successful Maven exit code is necessary,
//             * but a generated test suite must also exist.
//             */
//            if (!containsTestSource(projectDir)) {
//
//                previousFailure =
//                        "No test source was generated. " +
//                                "Generate meaningful JUnit tests.";
//
//                result.error = previousFailure;
//
//                continue;
//            }
//
//            /*
//             * Do not let the model decide whether this passed.
//             * Java determines pass from the actual checks.
//             */
//            result.pass = true;
//
//            result.status = "PASSED";
//
//            result.generatedCodeSummary =
//                    "Generated " + changes.size() + " file changes";
//
//            result.expectedTestResults =
//                    step.path("generateExpectedTest")
//                            .path("expectedResults").toString();
//
//            result.error = null;
//
//            return;
//        }
//
//        result.pass = false;
//
//        result.status = "FAILED";
//
//        throw new IllegalStateException(
//                stepKey + " failed after " +
//                        maxRetries + " attempts: " +
//                        result.error
//        );
//    }
//
//    // =========================================================
//    // FILE VALIDATION AND WRITING
//    // =========================================================
//
//    private void applyChanges(
//            Path projectDir,
//            JsonNode changes
//    ) throws IOException {
//
//        for (JsonNode change : changes) {
//
//            String relativePath =
//                    change.path("path").asText();
//
//            String content =
//                    change.path("content").asText();
//
//            if (relativePath.isBlank()) {
//
//                throw new IllegalArgumentException(
//                        "Generated file path is empty"
//                );
//            }
//
//            Path target =
//                    projectDir.resolve(relativePath).normalize();
//
//            if (!target.startsWith(projectDir)) {
//
//                throw new SecurityException(
//                        "File path escapes workspace: " +
//                                relativePath
//                );
//            }
//
//            String normalized =
//                    relativePath.replace('\\', '/');
//
//            if (normalized.startsWith(".git/") ||
//                    normalized.startsWith(".agent/") ||
//                    normalized.startsWith("target/") ||
//                    normalized.equals(".git") ||
//                    normalized.equals(".agent")) {
//
//                throw new SecurityException(
//                        "Protected file path: " + relativePath
//                );
//            }
//
//            Files.createDirectories(target.getParent());
//
//            Files.writeString(
//                    target,
//                    content,
//                    StandardCharsets.UTF_8
//            );
//        }
//    }
//
//    private String verifyExpectedFiles(
//            Path projectDir,
//            JsonNode expectedFiles
//    ) {
//
//        for (JsonNode file : expectedFiles) {
//
//            String relativePath = file.asText();
//
//            Path target =
//                    projectDir.resolve(relativePath).normalize();
//
//            if (!target.startsWith(projectDir) ||
//                    !Files.isRegularFile(target)) {
//
//                return "Expected file missing: " + relativePath;
//            }
//        }
//
//        return null;
//    }
//
//    private boolean containsTestSource(Path projectDir)
//            throws IOException {
//
//        Path testDirectory =
//                projectDir.resolve("src/test");
//
//        if (!Files.exists(testDirectory)) {
//            return false;
//        }
//
//        try (var paths = Files.walk(testDirectory)) {
//
//            return paths
//                    .anyMatch(path ->
//                            Files.isRegularFile(path) &&
//                                    path.toString().endsWith(".java"));
//        }
//    }
//
//    private String readProjectFiles(Path projectDir)
//            throws IOException {
//
//        if (!Files.exists(projectDir)) {
//            return "";
//        }
//
//        try (var paths = Files.walk(projectDir)) {
//
//            return paths
//                    .filter(Files::isRegularFile)
//                    .filter(path -> {
//
//                        String name =
//                                path.getFileName().toString();
//
//                        return name.endsWith(".java") ||
//                                name.endsWith(".xml") ||
//                                name.endsWith(".properties") ||
//                                name.endsWith(".yml") ||
//                                name.endsWith(".yaml");
//                    })
//                    .filter(path ->
//                            !path.toString().contains("target"))
//                    .limit(30)
//                    .map(path -> {
//
//                        try {
//
//                            String relative =
//                                    projectDir.relativize(path)
//                                            .toString();
//
//                            String content =
//                                    Files.readString(path);
//
//                            return "\n--- " + relative +
//                                    " ---\n" + content;
//
//                        } catch (IOException e) {
//
//                            return "";
//                        }
//                    })
//                    .collect(Collectors.joining("\n"));
//        }
//    }
//
//    // =========================================================
//    // MAVEN EXECUTION
//    // =========================================================
//
//    private CommandResult runMaven(
//            Path projectDir,
//            String... arguments
//    ) throws Exception {
//
//        if (!Files.isRegularFile(
//                projectDir.resolve("pom.xml"))) {
//
//            return new CommandResult(
//                    1,
//                    "pom.xml does not exist"
//            );
//        }
//
//        List<String> command = new ArrayList<>();
//
//        boolean windows = System.getProperty("os.name")
//                .toLowerCase()
//                .contains("win");
//
//        command.add(windows ? "mvn.cmd" : "mvn");
//
//        command.add("-B");
//
//        command.addAll(List.of(arguments));
//
//        ProcessBuilder builder =
//                new ProcessBuilder(command);
//
//        builder.directory(projectDir.toFile());
//
//        builder.redirectErrorStream(true);
//
//        Process process = builder.start();
//
//        ExecutorService reader =
//                Executors.newSingleThreadExecutor();
//
//        try {
//
//            var outputFuture = reader.submit(() -> {
//
//                try (var input = process.getInputStream()) {
//
//                    return new String(
//                            input.readAllBytes(),
//                            StandardCharsets.UTF_8
//                    );
//                }
//            });
//
//            boolean finished =
//                    process.waitFor(5, TimeUnit.MINUTES);
//
//            if (!finished) {
//
//                process.destroyForcibly();
//
//                throw new TimeoutException(
//                        "Maven execution exceeded 5 minutes"
//                );
//            }
//
//            String output =
//                    outputFuture.get(30, TimeUnit.SECONDS);
//
//            return new CommandResult(
//                    process.exitValue(),
//                    output
//            );
//
//        } finally {
//
//            if (process.isAlive()) {
//                process.destroyForcibly();
//            }
//
//            reader.shutdownNow();
//        }
//    }
//
//    // =========================================================
//    // OLLAMA CLIENT
//    // =========================================================
//
//    private String callOllama(String prompt)
//            throws Exception {
//
//        ObjectNode request = mapper.createObjectNode();
//
//        request.put("model", model);
//        request.put("prompt", prompt);
//        request.put("stream", false);
//
//        ObjectNode options = request.putObject("options");
//
//        options.put("temperature", 0.1);
//        options.put("num_ctx", 8192);
//
//        HttpRequest httpRequest =
//                HttpRequest.newBuilder()
//                        .uri(URI.create(
//                                ollamaUrl + "/api/generate"
//                        ))
//                        .timeout(Duration.ofMinutes(10))
//                        .header(
//                                "Content-Type",
//                                "application/json"
//                        )
//                        .POST(
//                                HttpRequest.BodyPublishers
//                                        .ofString(
//                                                mapper.writeValueAsString(
//                                                        request
//                                                )
//                                        )
//                        )
//                        .build();
//
//        HttpResponse<String> response =
//                httpClient.send(
//                        httpRequest,
//                        HttpResponse.BodyHandlers.ofString()
//                );
//
//        if (response.statusCode() < 200 ||
//                response.statusCode() >= 300) {
//
//            throw new IllegalStateException(
//                    "Ollama returned HTTP " +
//                            response.statusCode() +
//                            ": " + response.body()
//            );
//        }
//
//        JsonNode result =
//                mapper.readTree(response.body());
//
//        if (result.has("error")) {
//
//            throw new IllegalStateException(
//                    "Ollama error: " +
//                            result.path("error").asText()
//            );
//        }
//
//        String generated =
//                result.path("response").asText();
//
//        if (generated.isBlank()) {
//
//            throw new IllegalStateException(
//                    "Ollama returned an empty response"
//            );
//        }
//
//        return generated;
//    }
//
//    private JsonNode parseJson(String response)
//            throws IOException {
//
//        String cleaned = response.trim();
//
//        cleaned = cleaned
//                .replaceFirst("^```json\\s*", "")
//                .replaceFirst("^```\\s*", "")
//                .replaceFirst("\\s*```$", "");
//
//        try {
//
//            return mapper.readTree(cleaned);
//
//        } catch (Exception firstFailure) {
//
//            int start = cleaned.indexOf('{');
//            int end = cleaned.lastIndexOf('}');
//
//            if (start >= 0 && end > start) {
//
//                return mapper.readTree(
//                        cleaned.substring(start, end + 1)
//                );
//            }
//
//            throw firstFailure;
//        }
//    }
//
//    // =========================================================
//    // LOGGING
//    // =========================================================
//
//    private void log(Job job, String message) {
//
//        job.logs.add(
//                LocalDateTime.now() +
//                        " | " + message
//        );
//    }
//}