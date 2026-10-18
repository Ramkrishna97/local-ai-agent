package com.example.newaichatapp.agent.service;

import com.example.newaichatapp.agent.OllamaClient;
import com.example.newaichatapp.agent.model.PlanResponse;
import com.example.newaichatapp.agent.model.PlanStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private final OllamaClient ollama;
    private final ObjectMapper mapper;

    public PlannerService(OllamaClient ollama, ObjectMapper mapper) {
        this.ollama = ollama;
        this.mapper = mapper;
    }

    public PlanResponse createPlan(String request) {
        if (request == null || request.isBlank()) throw new IllegalArgumentException("Prompt is required");
        log.info("Planning started for request: \"{}\"", truncate(request, 150));

        String instructions = """
            You are a goal-oriented execution planner. Always return ONLY valid JSON in this format:
            {"goal":"one line goal","steps":[{"stepNumber":1,"description":"deliverable/action","type":"DIRECT","acceptanceCriteria":"specific success criteria"}],"end":"END"}
            Supported step types: DIRECT (stories, writing), JAVA (one standalone Java 17 source class), PROJECT (multi-file app with frameworks such as Spring Boot or React), COMMAND (installed tool version check only).
            If request includes multiple technologies/frameworks or multiple source files, use PROJECT steps, not JAVA.
            If the user explicitly asks for code/a program/a class/a function, at least one step MUST be type JAVA or PROJECT — never describe the code in a DIRECT step instead of producing it.
            This is ONLY the plan. Never generate output, source code, tests or commands in the plan.
            Plan the minimum steps needed to fulfill the user's request. A simple task often needs just ONE step.
            For a complex story, multiple meaningful stages are allowed (outline, characters, scenes, ending), with the final step producing the cohesive complete story.
            Each step must produce an identifiable artifact and have clear acceptance criteria.
            Never add a setup/filler step. Final step must satisfy the overall user request.
            Assume JDK, Node, and Ollama are installed. Do not plan installations.
            """;
        PlanResponse plan;
        String raw = ollama.generate(instructions + "\nUser request:\n" + request, true);
        try {
            plan = parse(raw, PlanResponse.class);
        } catch (IllegalArgumentException first) {
            log.warn("Plan JSON parse failed, retrying with a stricter repair prompt. Cause: {}", first.getMessage());
            String repair = "Return ONLY compact valid JSON with fields goal, steps array of {stepNumber,description,type,acceptanceCriteria}, end=END. " +
                "Supported types DIRECT, JAVA, PROJECT, COMMAND. No markdown. User request: " + request;
            plan = parse(ollama.generate(repair, true), PlanResponse.class);
        }
        if (!"END".equals(plan.end) || plan.steps == null || plan.steps.isEmpty() || plan.steps.size() > 12) {
            log.error("Planning failed: model returned an invalid plan shape (end={}, steps={})",
                    plan.end, plan.steps == null ? null : plan.steps.size());
            throw new IllegalArgumentException("Model returned an invalid plan");
        }
        for (int i = 0; i < plan.steps.size(); i++) {
            PlanStep step = plan.steps.get(i);
            if (step == null || step.stepNumber != i + 1 || blank(step.description) || blank(step.acceptanceCriteria)) {
                log.error("Planning failed: invalid step at position {}", i + 1);
                throw new IllegalArgumentException("Invalid plan step " + (i + 1));
            }
            if (!("DIRECT".equals(step.type) || "JAVA".equals(step.type) || "PROJECT".equals(step.type) || "COMMAND".equals(step.type))) {
                log.error("Planning failed: unsupported step type '{}' at step {}", step.type, step.stepNumber);
                throw new IllegalArgumentException("Unsupported step type for step " + step.stepNumber);
            }
            step.status = "PENDING";
        }
        plan.taskType = plan.steps.size() == 1 ? plan.steps.get(0).type : "PLAN";
        log.info("Planning succeeded: {} step(s), taskType={}, goal=\"{}\"",
                plan.steps.size(), plan.taskType, truncate(plan.goal, 100));
        return plan;
    }

    public PlanStep generateProject(PlanResponse plan, PlanStep step, String context) {
        String prompt = """
            Produce files for THIS step of a multi-file software project.
            Return ONLY valid JSON: {"files":{"relative/path/file.ext":"complete file contents"}}.
            Every path must be relative, with forward slashes. No markdown code fences or prose outside JSON.
            Include actual full code, not pseudo-code, directory trees, placeholders, TODOs, or prose descriptions.
            Do not overwrite existing files unless extending them intentionally.
            Use appropriate Gradle/Maven dependencies for Spring Boot and package.json for React.
            Keep the response focused on this step; subsequent steps add other files.
            Goal: %s
            Current step: %s
            Acceptance criteria: %s
            Earlier approved artifacts: %s
            """.formatted(plan.goal, step.description, step.acceptanceCriteria, limit(context, 5000));
        log.info("Generating PROJECT files for step {}: {}", step.stepNumber, truncate(step.description, 100));
        PlanStep result = parse(ollama.generate(prompt, true), PlanStep.class);
        if (result == null || result.files == null || result.files.isEmpty()) {
            log.warn("Step {}: model returned no project files on first attempt, retrying with a stricter prompt", step.stepNumber);
            result = parse(ollama.generate(prompt +
                    "\nYour previous answer had no files. You MUST return at least one file with real code in the \"files\" JSON object.",
                    true), PlanStep.class);
        }
        return result;
    }

    public PlanStep generateImplementation(PlanResponse plan, PlanStep step, String context) {
        String prompt = buildJavaImplementationPrompt(plan, step, context, false);
        log.info("Generating JAVA implementation for step {}: {}", step.stepNumber, truncate(step.description, 100));
        PlanStep result = parse(ollama.generate(prompt, true), PlanStep.class);

        if (!looksLikeCode(result == null ? null : result.generatedCode)) {
            // The model answered with an explanation/description instead of actual source — a known
            // failure mode on small models even under format:json. One retry with an explicit,
            // sharper instruction and a concrete counter-example usually fixes it.
            log.warn("Step {}: generated content doesn't look like Java source, retrying with a stricter code-only prompt", step.stepNumber);
            String stricter = buildJavaImplementationPrompt(plan, step, context, true);
            result = parse(ollama.generate(stricter, true), PlanStep.class);
            if (!looksLikeCode(result == null ? null : result.generatedCode)) {
                log.error("Step {}: model still returned non-code content after retry", step.stepNumber);
                throw new IllegalArgumentException(
                        "Model returned a description instead of Java source code, even after a stricter retry. " +
                        "Try a more capable model, or simplify the request.");
            }
        }
        return result;
    }

    private String buildJavaImplementationPrompt(PlanResponse plan, PlanStep step, String context, boolean strict) {
        String base = """
            Implement ONE Java 17 step. Return ONLY JSON: {"className":"ClassName","generatedCode":"complete package-less Java source"}.
            The "generatedCode" value must be REAL, COMPLETE, COMPILABLE Java source code — actual syntax (class, methods, braces),
            never a plain-English description, summary, or explanation of what the code would do.
            No test class, no markdown, no explanations outside the JSON. Only Java standard library.
            Produce working behavior, not stubs. Include main(String[] args) when the user requested an executable application.
            Goal: %s
            Current step: %s
            Acceptance criteria: %s
            Previous accepted artifacts: %s
            """.formatted(plan.goal, step.description, step.acceptanceCriteria, limit(context, 10000));
        if (!strict) return base;
        return base + """

            IMPORTANT: your last answer was rejected because "generatedCode" contained prose instead of code.
            WRONG example: "generatedCode": "This class will read a file and print its contents line by line."
            RIGHT example: "generatedCode": "public class Reader { public static void main(String[] a) { ... } }"
            Return ONLY actual Java source in "generatedCode" this time, starting with "public class" or "class".
            """;
    }

    /**
     * Cheap heuristic to catch the "model described the code instead of writing it" failure mode:
     * real Java source has braces, semicolons and a class/interface declaration; a prose description
     * of code typically has neither, or has suspiciously few of them relative to its length.
     */
    private boolean looksLikeCode(String code) {
        if (code == null || code.isBlank()) return false;
        String trimmed = code.strip();
        boolean hasClassDecl = Pattern.compile("\\b(class|interface|enum|record)\\s+\\w+").matcher(trimmed).find();
        boolean hasBraces = trimmed.contains("{") && trimmed.contains("}");
        long semicolons = trimmed.chars().filter(c -> c == ';').count();
        // require at least a class/interface declaration, braces, and a plausible density of statements
        return hasClassDecl && hasBraces && semicolons >= 1;
    }

    public String generateDirect(PlanResponse plan, PlanStep step, String context) {
        log.info("Generating DIRECT output for step {}: {}", step.stepNumber, truncate(step.description, 100));
        return ollama.generate("Execute the CURRENT step, return its deliverable, not a plan. " +
            "Keep continuity with previous accepted work. If this is the final step, give the coherent completed artifact.\n" +
            "Goal: " + plan.goal + "\nStep: " + step.description +
            "\nAcceptance criteria: " + step.acceptanceCriteria +
            "\nEarlier accepted output: " + limit(context, 14000), false);
    }

    public String revise(PlanResponse plan, PlanStep step, String previous, String feedback, String context) {
        log.info("Revising step {} after reviewer feedback: {}", step.stepNumber, truncate(feedback, 150));
        String resultFormat = "PROJECT".equals(step.type)
            ? "Return ONLY valid JSON: {\"files\":{\"path/to/file.ext\":\"complete code\"}}. No markdown or placeholders."
            : "JAVA".equals(step.type)
            ? "Return ONLY JSON: {\"className\":\"ClassName\",\"generatedCode\":\"full package-less Java 17 source\"}. " +
              "generatedCode must be real compilable code, never a description. No tests."
            : "Return ONLY the revised deliverable, not a verdict or explanation.";
        String revised = ollama.generate("Revise the step output to satisfy the acceptance criteria. " + resultFormat +
            "\nOverall goal: " + plan.goal + "\nCurrent step: " + step.description +
            "\nAcceptance criteria: " + step.acceptanceCriteria +
            "\nPrevious accepted steps: " + limit(context, 6000) +
            "\nPrevious output: " + limit(previous, 16000) +
            "\nReviewer feedback: " + limit(feedback, 5000), ("JAVA".equals(step.type) || "PROJECT".equals(step.type)));

        if ("JAVA".equals(step.type)) {
            PlanStep parsed = parse(revised, PlanStep.class);
            if (!looksLikeCode(parsed.generatedCode)) {
                log.warn("Step {}: revision also returned non-code content, treating as a failed revision", step.stepNumber);
                throw new IllegalArgumentException("Revised output was a description instead of Java source code");
            }
        }
        return revised;
    }

    public PlanStep parseImplementation(String raw) { return parse(raw, PlanStep.class); }

    public <T> T parse(String raw, Class<T> type) {
        try {
            String s = raw == null ? "" : raw.strip();
            if (s.startsWith("```")) s = s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            if (s.isBlank()) throw new IllegalArgumentException("Empty generated JSON");
            int left = s.indexOf('{'), right = s.lastIndexOf('}');
            if (left >= 0 && right > left) s = s.substring(left, right + 1);
            return mapper.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON from Ollama: " + e.getMessage(), e);
        }
    }

    private boolean blank(String s) { return s == null || s.isBlank(); }
    private String limit(String s, int length) { return s == null ? "" : s.substring(0, Math.min(s.length(), length)); }
    private String truncate(String s, int length) {
        if (s == null) return "";
        return s.length() <= length ? s : s.substring(0, length) + "...";
    }
}
