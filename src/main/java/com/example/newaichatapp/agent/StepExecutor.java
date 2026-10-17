package com.example.newaichatapp.agent;

import com.example.newaichatapp.agent.config.AgentConfig;
import com.example.newaichatapp.agent.model.PlanStep;
import com.example.newaichatapp.agent.model.ProgressEvent;
import com.example.newaichatapp.agent.service.JavaCodeRunner;
import com.example.newaichatapp.agent.service.PlannerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;

@Service
public class StepExecutor {

    private final JavaCodeRunner runner;
    private final PlannerService plannerService;

    @Autowired
    public StepExecutor(JavaCodeRunner runner, PlannerService plannerService) {
        this.runner = runner;
        this.plannerService = plannerService;
    }

    /** Executes all steps in order; stops at first step that can't be made to pass within MAX_RETRIES. */
    public boolean executeAll(List<PlanStep> steps) {
        return executeAll(steps, null);
    }

    /** Same as executeAll, but emits a ProgressEvent after every notable sub-step via onProgress (may be null). */
    public boolean executeAll(List<PlanStep> steps, Consumer<ProgressEvent> onProgress) {
        Path baseWorkDir;
        try {
            baseWorkDir = Files.createTempDirectory("planner-agent-");
        } catch (IOException e) {
            throw new RuntimeException("Could not create working directory", e);
        }
        int failedCount = 0;

        for (PlanStep step : steps) {
            boolean passed = executeStepWithRetries(step, baseWorkDir, onProgress);
            step.status = passed ? "PASSED" : "FAILED";
            if (!passed) failedCount++;

            ProgressEvent resultEvent = ProgressEvent.of("STEP_RESULT",
                    "Step " + step.stepNumber + ": " + (passed ? "passed" : "failed") +
                            (step.attempts > 1 ? " after " + step.attempts + " attempts" : ""));
            resultEvent.stepNumber = step.stepNumber;
            resultEvent.status = step.status;
            resultEvent.attempt = step.attempts;
            resultEvent.step = step;
            emit(onProgress, resultEvent);

            if (!passed && !AgentConfig.CONTINUE_ON_STEP_FAILURE) {
                ProgressEvent bail = ProgressEvent.of("STEP_PHASE",
                        "Step " + step.stepNumber + " failed; stopping pipeline (continueOnFailure=false).");
                bail.stepNumber = step.stepNumber;
                emit(onProgress, bail);
                return false;
            }
        }

        if (failedCount > 0 && AgentConfig.CONTINUE_ON_STEP_FAILURE) {
            ProgressEvent summary = ProgressEvent.of("STEP_PHASE",
                    "Completed all " + steps.size() + " step(s) with " + failedCount +
                            " failing test(s). Generated code for every step is still available.");
            emit(onProgress, summary);
        }
        return failedCount == 0;
    }

    private boolean executeStepWithRetries(PlanStep step, Path baseWorkDir, Consumer<ProgressEvent> onProgress) {
        ProgressEvent startEvent = ProgressEvent.of("STEP_START",
                "Step " + step.stepNumber + ": " + step.description);
        startEvent.stepNumber = step.stepNumber;
        startEvent.stepDescription = step.description;
        startEvent.status = "RUNNING";
        emit(onProgress, startEvent);

        for (int attempt = 1; attempt <= AgentConfig.MAX_RETRIES_PER_STEP; attempt++) {
            step.attempts = attempt;
            try {
                if (runOneAttempt(step, baseWorkDir, onProgress)) {
                    return true;
                }
                String feedback = "generatedCode compiled/ran but test did not match expectedResult.\n" +
                        "compileOutput: " + step.compileOutput + "\n" +
                        "runOutput: " + step.runOutput + "\n" +
                        "testRunOutput: " + step.testRunOutput + "\n" +
                        "expectedResult: " + step.expectedResult;
                step.lastError = feedback;
                phaseEvent(onProgress, step, "test-mismatch",
                        "Step " + step.stepNumber + ": test ran but output didn't match expected result");
            } catch (Exception e) {
                step.lastError = stackTraceToString(e);
                phaseEvent(onProgress, step, "error",
                        "Step " + step.stepNumber + ": " + firstLine(e.getMessage()));
            }

            if (attempt < AgentConfig.MAX_RETRIES_PER_STEP) {
                ProgressEvent retryEvent = ProgressEvent.of("STEP_RETRY",
                        "Step " + step.stepNumber + ": asking the model to fix attempt " + attempt + "'s error\u2026");
                retryEvent.stepNumber = step.stepNumber;
                retryEvent.attempt = attempt;
                retryEvent.status = "RUNNING";
                emit(onProgress, retryEvent);

                try {
                    PlanStep fixed = plannerService.fixStep(step, step.lastError);
                    step.generatedCode = fixed.generatedCode;
                    step.className = fixed.className;
                    step.testCode = fixed.testCode;
                    step.testClassName = fixed.testClassName;
                    if (fixed.expectedResult != null && !fixed.expectedResult.isBlank()) {
                        step.expectedResult = fixed.expectedResult;
                    }
                    phaseEvent(onProgress, step, "fix-received",
                            "Step " + step.stepNumber + ": got a fix from the model, retrying\u2026");
                } catch (Exception fixEx) {
                    step.lastError += "\n\nAdditionally failed to get a fix from the model: " + fixEx.getMessage();
                    phaseEvent(onProgress, step, "fix-failed",
                            "Step " + step.stepNumber + ": couldn't get a fix (" +
                                    firstLine(fixEx.getMessage()) + "), retrying same code\u2026");
                }
            }
        }
        return false;
    }

    /** One compile+run+test pass. Returns true only if test output matches expectedResult. Throws on infra errors. */
    private boolean runOneAttempt(PlanStep step, Path baseWorkDir, Consumer<ProgressEvent> onProgress) throws Exception {
        Path stepDir = baseWorkDir.resolve("step-" + step.stepNumber + "-attempt-" + step.attempts);

        // 1. compile generatedCode
        phaseEvent(onProgress, step, "compiling", "Step " + step.stepNumber + ": compiling generated code\u2026");
        Path codeDir = stepDir.resolve("code");
        JavaCodeRunner.CompileResult codeCompile = runner.compile(step.generatedCode, step.className, codeDir);
        step.compileOutput = codeCompile.diagnostics;
        if (!codeCompile.success) {
            throw new RuntimeException("Compilation failed for step " + step.stepNumber + ":\n" + codeCompile.diagnostics);
        }

// 2. optionally run generatedCode's main() — NOT required, and never a hard failure.
//     A helper class only needs to *compile* so the test can call it. Many valid
//     generated classes have no main() at all (e.g. a PrimeChecker utility class).
        if (AgentConfig.RUN_GENERATED_CODE_STANDALONE) {
            phaseEvent(onProgress, step, "running", "Step " + step.stepNumber + ": running generated code\u2026");
            JavaCodeRunner.RunResult codeRun = runner.run(codeDir, step.className);
            step.runOutput = (codeRun.stdout == null ? "" : codeRun.stdout) +
                    (codeRun.stderr == null ? "" : codeRun.stderr);
            if (codeRun.timedOut) {
                throw new RuntimeException("generatedCode for step " + step.stepNumber + " timed out:\n" + step.runOutput);
            }
            if (!codeRun.success) {
                throw new RuntimeException("generatedCode for step " + step.stepNumber +
                        " exited with code " + codeRun.exitCode + ":\n" + step.runOutput);
            }
        } else {
            step.runOutput = "(skipped standalone run; only compilation + test are enforced)";
        }

        // 3. compile testCode (copy generatedCode's .class files alongside so the test can reference it)
        phaseEvent(onProgress, step, "compiling-test", "Step " + step.stepNumber + ": compiling test\u2026");
        Path testDir = stepDir.resolve("test");
        Files.createDirectories(testDir);
        copyClassFiles(codeDir, testDir);
        JavaCodeRunner.CompileResult testCompile = runner.compile(step.testCode, step.testClassName, testDir);
        step.testRunOutput = testCompile.diagnostics;
        if (!testCompile.success) {
            throw new RuntimeException("Test compilation failed for step " + step.stepNumber + ":\n" + testCompile.diagnostics);
        }

// 4. run testCode
        phaseEvent(onProgress, step, "testing", "Step " + step.stepNumber + ": running test\u2026");
        JavaCodeRunner.RunResult testRun = runner.run(testDir, step.testClassName);
        String testOutput = (testRun.stdout == null ? "" : testRun.stdout) +
                (testRun.stderr == null ? "" : testRun.stderr);
        step.testRunOutput = testOutput;

        return strictPassCheck(step, testRun, testOutput, onProgress);
    }

    private void phaseEvent(Consumer<ProgressEvent> onProgress, PlanStep step, String phase, String message) {
        ProgressEvent e = ProgressEvent.of("STEP_PHASE", message);
        e.stepNumber = step.stepNumber;
        e.phase = phase;
        e.attempt = step.attempts;
        e.status = "RUNNING";
        emit(onProgress, e);
    }

    private void emit(Consumer<ProgressEvent> onProgress, ProgressEvent event) {
        if (onProgress != null) {
            try {
                onProgress.accept(event);
            } catch (Exception ignored) {
                // a broken/closed client stream shouldn't abort the actual pipeline run
            }
        }
    }

    private String firstLine(String s) {
        if (s == null) return "unknown error";
        int nl = s.indexOf('\n');
        return nl > 0 ? s.substring(0, nl) : s;
    }

    private void copyClassFiles(Path from, Path to) throws IOException {
        if (!Files.exists(from)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(from, "*.class")) {
            for (Path p : stream) {
                Files.copy(p, to.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private String stackTraceToString(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Authoritative pass decision for a step. Uses ONLY real, observable facts from
     * the test subprocess — not a substring the model could print unconditionally.
     * All four rules must hold:
     *   1. test subprocess did not time out
     *   2. test subprocess exited with code 0 (this is what catches every uncaught
     *      exception / StackOverflowError / assertion failure in the test's main)
     *   3. test stdout does not contain RESULT:FAIL
     *   4. test stdout contains the expected pass marker (default "RESULT:PASS")
     */
    private boolean strictPassCheck(PlanStep step,
                                    JavaCodeRunner.RunResult testRun,
                                    String testOutput,
                                    Consumer<ProgressEvent> onProgress) {

        if (testRun.timedOut) {
            phaseEvent(onProgress, step, "test-timeout",
                    "Step " + step.stepNumber + ": test timed out \u2717");
            return false;
        }
        if (testRun.exitCode != 0) {
            phaseEvent(onProgress, step, "test-exit-nonzero",
                    "Step " + step.stepNumber + ": test exited with code " + testRun.exitCode + " \u2717");
            return false;
        }
        if (testOutput.contains("RESULT:FAIL")) {
            phaseEvent(onProgress, step, "test-failed",
                    "Step " + step.stepNumber + ": test printed RESULT:FAIL \u2717");
            return false;
        }
        String expected = step.expectedResult == null ? "RESULT:PASS" : step.expectedResult;
        if (!testOutput.contains(expected)) {
            phaseEvent(onProgress, step, "test-no-marker",
                    "Step " + step.stepNumber + ": test did not print " + expected + " \u2717");
            return false;
        }
        phaseEvent(onProgress, step, "test-passed",
                "Step " + step.stepNumber + ": test passed \u2713 (exit 0 + " + expected + ")");
        return true;
    }
}