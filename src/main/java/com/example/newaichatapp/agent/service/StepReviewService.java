package com.example.newaichatapp.agent.service;

import com.example.newaichatapp.agent.OllamaClient;
import com.example.newaichatapp.agent.model.PlanResponse;
import com.example.newaichatapp.agent.model.PlanStep;
import com.example.newaichatapp.agent.model.ReviewVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StepReviewService {

    private static final Logger log = LoggerFactory.getLogger(StepReviewService.class);

    private final OllamaClient ollama;
    private final PlannerService planner;

    public StepReviewService(OllamaClient ollama, PlannerService planner) {
        this.ollama = ollama;
        this.planner = planner;
    }

    public ReviewVerdict review(PlanResponse plan, PlanStep step, String artifact, String previousContext) {
        log.info("Reviewing step {} ({}): {}", step.stepNumber, step.type, truncate(step.description, 100));

        String javaLeniency = "JAVA".equals(step.type)
            ? "This Java code already compiled and ran successfully before reaching you — compilation and " +
              "basic execution are already verified separately, so do NOT reject it for things like missing " +
              "imports, syntax, or 'would it compile' concerns (it does). Only reject it if it clearly fails to " +
              "implement the step's actual requirement (wrong behavior, missing a specifically requested feature, " +
              "or a logic bug that would produce wrong results for the described use case). When in doubt between " +
              "approving working code and rejecting it over a minor stylistic concern, approve it.\n"
            : "";

        String prompt = """
            You are a careful reviewer of an agent's step output, NOT an author.
            Evaluate whether the supplied artifact actually satisfies THIS step's acceptance criteria and contributes to the user's goal.
            Do not demand tests, a special marker, or stylistic perfection. Be practical and fair.
            %s
            For JAVA, inspect code for logic, requirements, edge cases and glaring correctness defects. Compilation is checked separately.
            For DIRECT, judge the text according to the planned deliverable (e.g., baseline, characters, story scene, full ending).
            A generic placeholder or claim of completion is insufficient. Do not invent requirements.
            Return ONLY JSON, exactly: {"matches":true,"reasoning":"brief grounded explanation","improvements":"specific change if failed, otherwise empty"}.
            Be skeptical of genuinely missing/wrong work, but do not reject good, working code over unnecessary details.
            Goal: %s
            Step %d: %s
            Type: %s
            Acceptance criteria: %s
            Previous accepted artifacts: %s
            Artifact to review:
%s
            """.formatted(javaLeniency, plan.goal, step.stepNumber, step.description, step.type,
                step.acceptanceCriteria, limit(previousContext, 7000), limit(artifact, 18000));

        ReviewVerdict verdict = planner.parse(ollama.generate(prompt, true), ReviewVerdict.class);
        if (verdict == null || verdict.reasoning == null || verdict.reasoning.isBlank()) {
            log.error("Step {}: reviewer returned an incomplete verdict", step.stepNumber);
            throw new IllegalArgumentException("Reviewer returned an incomplete verdict");
        }

        if (verdict.matches) {
            log.info("Step {} review PASSED: {}", step.stepNumber, truncate(verdict.reasoning, 200));
        } else {
            log.warn("Step {} review FAILED: {} | suggested improvement: {}",
                    step.stepNumber, truncate(verdict.reasoning, 200), truncate(verdict.improvements, 200));
        }
        return verdict;
    }

    private String limit(String s, int length) { return s == null ? "" : s.substring(0, Math.min(s.length(), length)); }
    private String truncate(String s, int length) {
        if (s == null) return "";
        return s.length() <= length ? s : s.substring(0, length) + "...";
    }
}
