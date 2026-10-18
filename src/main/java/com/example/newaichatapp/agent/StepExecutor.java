package com.example.newaichatapp.agent;

import com.example.newaichatapp.agent.config.AgentConfig;
import com.example.newaichatapp.agent.model.PlanResponse;
import com.example.newaichatapp.agent.model.PlanStep;
import com.example.newaichatapp.agent.model.ProgressEvent;
import com.example.newaichatapp.agent.model.ReviewVerdict;
import com.example.newaichatapp.agent.service.JavaCodeRunner;
import com.example.newaichatapp.agent.service.LocalCommandRunner;
import com.example.newaichatapp.agent.service.PlannerService;
import com.example.newaichatapp.agent.service.StepReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

@Service
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final JavaCodeRunner runner;
    private final PlannerService planner;
    private final StepReviewService reviewer;
    private final LocalCommandRunner commands;

    public StepExecutor(JavaCodeRunner runner, PlannerService planner,
                        StepReviewService reviewer, LocalCommandRunner commands) {
        this.runner = runner;
        this.planner = planner;
        this.reviewer = reviewer;
        this.commands = commands;
    }

    public boolean executeAll(PlanResponse plan, Consumer<ProgressEvent> sink) {
        try {
            Path workspace = Files.createTempDirectory("agent-workspace-");
            StringBuilder acceptedContext = new StringBuilder();
            boolean allPassed = true;
            log.info("Execution started: {} step(s), goal=\"{}\"", plan.steps.size(), plan.goal);
            for (PlanStep step : plan.steps) {
                checkCancelled();
                step.status = "RUNNING";
                log.info("Step {}/{} starting ({}): {}", step.stepNumber, plan.steps.size(), step.type, step.description);
                emit(sink, step, "STEP_START", step.description, "start");
                boolean passed = executeStep(plan, step, workspace, acceptedContext, sink);
                step.status = passed ? "PASSED" : "FAILED";
                if (passed) {
                    log.info("Step {}/{} PASSED after {} attempt(s)", step.stepNumber, plan.steps.size(), step.attempts);
                } else {
                    log.warn("Step {}/{} FAILED after {} attempt(s): {}",
                            step.stepNumber, plan.steps.size(), step.attempts, step.lastError);
                }
                emit(sink, step, "STEP_RESULT", "Step " + step.stepNumber + ": " + step.status, "result");
                if (!passed) {
                    allPassed = false;
                    if (!AgentConfig.CONTINUE_ON_STEP_FAILURE) {
                        log.warn("Stopping run: step {} failed and CONTINUE_ON_STEP_FAILURE is false", step.stepNumber);
                        break;
                    }
                }
            }
            log.info("Execution finished: overallSuccess={}", allPassed);
            return allPassed;
        } catch (Exception e) {
            log.error("Execution aborted by an infrastructure error", e);
            throw new IllegalStateException("Execution failed", e);
        }
    }

    public boolean executeAll(List<PlanStep> steps, Consumer<ProgressEvent> sink) {
        PlanResponse plan = new PlanResponse(); plan.goal = "Execute steps"; plan.steps = steps;
        return executeAll(plan, sink);
    }
    public boolean executeAll(List<PlanStep> steps) { return executeAll(steps, null); }

    private boolean executeStep(PlanResponse plan, PlanStep step, Path workspace,
                                StringBuilder acceptedContext, Consumer<ProgressEvent> sink) {
        String artifact = null;
        String feedback = "";
        int maxAttempts = Math.max(1, AgentConfig.MAX_RETRIES_PER_STEP);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            checkCancelled();
            step.attempts = attempt;
            log.info("Step {} attempt {}/{}", step.stepNumber, attempt, maxAttempts);
            try {
                if (attempt == 1) {
                    artifact = generate(plan, step, acceptedContext.toString());
                } else {
                    log.info("Step {} attempt {}: revising from reviewer feedback: {}",
                            step.stepNumber, attempt, truncate(feedback, 200));
                    emit(sink, step, "STEP_RETRY", "Revising artifact from reviewer feedback", "retry");
                    artifact = revise(plan, step, artifact, feedback, acceptedContext.toString());
                }
                if (artifact == null || artifact.isBlank()) throw new IllegalArgumentException("Empty generated artifact");
                log.info("Step {} attempt {}: artifact generated ({} chars), validating", step.stepNumber, attempt, artifact.length());
                emit(sink, step, "STEP_PHASE", "Generated artifact; validating requirements", "reviewing");

                if ("PROJECT".equals(step.type)) {
                    writeProjectFiles(step, workspace);
                    log.info("Step {}: project files saved to workspace ({} file(s))", step.stepNumber, step.files.size());
                    emit(sink, step, "STEP_PHASE", "Project files saved (build verification requires project dependencies)", "files-saved");
                }
                if ("JAVA".equals(step.type)) {
                    JavaCodeRunner.CompileResult compiled = runner.compile(step.generatedCode, step.className, workspace);
                    step.compileOutput = compiled.diagnostics;
                    if (!compiled.success) {
                        feedback = "Java compilation failed: " + compiled.diagnostics;
                        step.lastError = feedback;
                        log.warn("Step {} attempt {}: COMPILE FAILED: {}", step.stepNumber, attempt, truncate(compiled.diagnostics, 300));
                        emit(sink, step, "STEP_PHASE", feedback, "compile-failed");
                        continue;
                    }
                    log.info("Step {} attempt {}: compile PASSED (class {})", step.stepNumber, attempt, step.className);
                    emit(sink, step, "STEP_PHASE", "Java compilation succeeded", "compiled");
                }

                checkCancelled();
                ReviewVerdict verdict = reviewer.review(plan, step, artifact, acceptedContext.toString());
                checkCancelled();
                step.reviewReasoning = verdict.reasoning;
                step.reviewPassed = verdict.matches;
                if (!verdict.matches) {
                    feedback = verdict.improvements == null || verdict.improvements.isBlank()
                        ? verdict.reasoning : verdict.improvements;
                    step.lastError = "Review rejected artifact: " + feedback;
                    log.warn("Step {} attempt {}: REVIEW FAILED: {}", step.stepNumber, attempt, truncate(feedback, 300));
                    emit(sink, step, "STEP_PHASE", step.lastError, "review-failed");
                    continue;
                }
                step.lastError = null;
                acceptedContext.append("\nStep ").append(step.stepNumber).append(" (approved): ")
                    .append(artifact.length() > 10000 ? artifact.substring(0, 10000) : artifact);
                log.info("Step {} attempt {}: REVIEW PASSED: {}", step.stepNumber, attempt, truncate(verdict.reasoning, 200));
                emit(sink, step, "STEP_PHASE", "Reviewer approved: " + verdict.reasoning, "review-passed");
                return true;
            } catch (Exception e) {
                checkCancelled();
                feedback = e.getMessage() == null ? e.toString() : e.getMessage();
                step.lastError = feedback;
                log.warn("Step {} attempt {}: ERROR: {}", step.stepNumber, attempt, feedback);
                emit(sink, step, "STEP_PHASE", "Step error: " + feedback, "error");
            }
        }
        return false;
    }

    private void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Run cancelled"); }

    private String generate(PlanResponse plan, PlanStep step, String context) {
        return switch (step.type) {
            case "JAVA" -> {
                PlanStep produced = planner.generateImplementation(plan, step, context);
                applyJava(step, produced);
                yield step.generatedCode;
            }
            case "PROJECT" -> {
                applyProject(step, planner.generateProject(plan, step, context));
                yield step.generatedCode;
            }
            case "DIRECT" -> {
                step.runOutput = planner.generateDirect(plan, step, context);
                yield step.runOutput;
            }
            case "COMMAND" -> {
                step.runOutput = commands.toolVersion(selectTool(step.description));
                yield step.runOutput;
            }
            default -> throw new IllegalArgumentException("Unsupported type: " + step.type);
        };
    }

    private String revise(PlanResponse plan, PlanStep step, String old, String feedback, String context) {
        if ("COMMAND".equals(step.type)) return generate(plan, step, context);
        String revised = planner.revise(plan, step, old, feedback, context);
        if ("PROJECT".equals(step.type)) {
            applyProject(step, planner.parseImplementation(revised));
            return step.generatedCode;
        }
        if ("JAVA".equals(step.type)) {
            applyJava(step, planner.parseImplementation(revised));
            return step.generatedCode;
        }
        step.runOutput = revised;
        return revised;
    }

    private void applyProject(PlanStep step, PlanStep produced) {
        if (produced == null || produced.files == null || produced.files.isEmpty())
            throw new IllegalArgumentException("Model returned no project files");
        step.files = new java.util.LinkedHashMap<>(produced.files);
        StringBuilder artifact = new StringBuilder();
        produced.files.forEach((name, content) -> artifact.append("\n### ").append(name)
            .append("\n```\n").append(content).append("\n```\n"));
        step.generatedCode = artifact.toString();
    }

    private void writeProjectFiles(PlanStep step, Path workspace) throws java.io.IOException {
        Path root = workspace.resolve("project").toAbsolutePath().normalize();
        Files.createDirectories(root);
        for (var entry : step.files.entrySet()) {
            String relative = entry.getKey().replace('\\', '/');
            if (relative.startsWith("/") || relative.contains(":") || relative.contains(".."))
                throw new IllegalArgumentException("Unsafe project path: " + relative);
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("Unsafe project path");
            if (entry.getValue() == null) throw new IllegalArgumentException("Missing file content: " + relative);
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue());
        }
    }

    private void applyJava(PlanStep step, PlanStep produced) {
        if (produced == null || produced.className == null ||
            !produced.className.matches("[A-Za-z_$][A-Za-z0-9_$]*") ||
            produced.generatedCode == null || produced.generatedCode.isBlank())
            throw new IllegalArgumentException("Ollama did not return valid Java class/source");
        step.className = produced.className;
        step.generatedCode = produced.generatedCode;
    }

    private String selectTool(String description) {
        String s = description.toLowerCase();
        if (s.contains("javac")) return "javac";
        if (s.contains("node")) return "node";
        if (s.contains("npm")) return "npm";
        if (s.contains("ollama")) return "ollama";
        if (s.contains("java") || s.contains("jdk")) return "java";
        throw new IllegalArgumentException("Unrecognized tool version check: " + description);
    }

    private void emit(Consumer<ProgressEvent> sink, PlanStep step, String type, String message, String phase) {
        if (sink == null) return;
        ProgressEvent e = ProgressEvent.of(type, message);
        e.stepNumber = step.stepNumber; e.stepDescription = step.description;
        e.phase = phase; e.attempt = step.attempts; e.status = step.status;
        if ("STEP_RESULT".equals(type)) e.step = step;
        try { sink.accept(e); } catch (Exception ignored) { }
    }

    private String truncate(String s, int length) {
        if (s == null) return "";
        return s.length() <= length ? s : s.substring(0, length) + "...";
    }
}
