package com.example.newaichatapp.agent.service;


import com.example.newaichatapp.agent.OllamaClient;
import com.example.newaichatapp.agent.model.PlanResponse;
import com.example.newaichatapp.agent.model.PlanStep;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlannerService {

    private final OllamaClient ollamaClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlannerService(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    private static final String SCHEMA_INSTRUCTIONS = """
        You are a coding planner agent. Given a user's coding request, break it into an ordered
        sequence of small, independently compilable and testable Java steps.

        You MUST respond with ONLY valid JSON, no markdown fences, no commentary, matching EXACTLY
        this schema:

        {
          "goal": "<one line summary of the overall task>",
          "steps": [
            {
              "stepNumber": 1,
              "description": "<what this step implements>",
              "generatedCode": "<COMPLETE compilable Java source, single public class, package-less>",
              "className": "<the public class name used in generatedCode, must match exactly>",
              "testCode": "<COMPLETE compilable Java source, single public class with a main method that VERIFIES generatedCode — see TEST CONTRACT>",
              "testClassName": "<the public class name used in testCode>",
              "expectedResult": "RESULT:PASS",
              "status": "PENDING"
            }
          ],
          "end": "END"
        }

        ============================================================
        RULES FOR generatedCode
        ============================================================
        - Must be FULLY self-contained: single public class, no package declaration,
          no imports of other steps' classes. Every step is compiled and run in isolation.
        - Does NOT need a main() method. Do not add one unless the step is genuinely a
          standalone program. Helper / utility classes typically should not have main().
        - Only java.* / javax.* standard library. No third-party dependencies.
        - Keep each step small and independently verifiable.

        ============================================================
        TEST CONTRACT — the most important part.
        The test MUST actually verify behaviour. A test that just prints
        "RESULT:PASS" without calling the generated code is USELESS and will be rejected.

        The test class MUST:
          1. Have exactly one public class matching testClassName, with a main(String[] args).
          2. In main(), instantiate or call at least one public member of generatedCode's class.
          3. Capture the ACTUAL value / output produced.
          4. Compare ACTUAL against a hard-coded EXPECTED value you choose.
          5. Print three lines, in this exact order, one per System.out.println call:
                 ACTUAL: <actual value>
                 EXPECTED: <expected value>
                 RESULT:PASS        <- only when ACTUAL equals EXPECTED
             OR, when they differ, the same ACTUAL / EXPECTED lines followed by:
                 RESULT:FAIL
          6. NEVER print RESULT:PASS unconditionally. The test must be capable of failing
             when generatedCode is wrong.

        Example of an acceptable test for a step whose generatedCode is
        `public class Sum { public int add(int a, int b) { return a + b; } }`:

            public class SumTest {
                public static void main(String[] args) {
                    int actual = new Sum().add(2, 3);
                    int expected = 5;
                    System.out.println("ACTUAL: " + actual);
                    System.out.println("EXPECTED: " + expected);
                    System.out.println(actual == expected ? "RESULT:PASS" : "RESULT:FAIL");
                }
            }

        expectedResult must always be the literal string "RESULT:PASS"; the test itself
        decides whether it actually passes.
        ============================================================

        Other rules:
        - Order steps so later ones depend only on concepts already established by earlier ones.
        - Always end the JSON with "end": "END".
        - Output raw JSON only. No ```json fences. No prose before or after.
        """;

    public PlanResponse createPlan(String userPrompt) {
        String fullPrompt = SCHEMA_INSTRUCTIONS + "\n\nUser request:\n" + userPrompt;
        String raw = ollamaClient.generate(fullPrompt, true);
        return parsePlan(raw);
    }

    /** Asks the model to fix one step's code given an exception, returns the corrected PlanStep fields. */
    public PlanStep fixStep(PlanStep failingStep, String errorDetails) {
        String fixPrompt = """
        The following Java code for a planner-agent step failed to compile or run, or failed its test.

        Step description: %s

        generatedCode:
        %s

        testCode:
        %s

        Error / failure details:
        %s

        Respond with ONLY valid JSON matching EXACTLY this schema (same as before), with corrected
        generatedCode and testCode that fixes the problem.

        Requirements:
        - generatedCode must be a single public class matching className, package-less, self-contained.
        - generatedCode does NOT need a main() unless the step is genuinely a standalone program.
          Do not add a main() just to silence an error unless the error is specifically about a
          missing main().
        - testCode must genuinely verify generatedCode: print ACTUAL / EXPECTED lines and then
          RESULT:PASS when they match, RESULT:FAIL when they don't. Never print RESULT:PASS
          unconditionally. Only java.* / javax.* standard library.
        - Keep className / testClassName the same unless the error requires renaming.

        {
          "stepNumber": %d,
          "description": "%s",
          "generatedCode": "<fixed complete Java source>",
          "className": "%s",
          "testCode": "<fixed complete Java source>",
          "testClassName": "%s",
          "expectedResult": "RESULT:PASS",
          "status": "PENDING"
        }

        Output raw JSON only. No markdown fences. No prose.
        """.formatted(
                failingStep.description,
                nullToEmpty(failingStep.generatedCode),
                nullToEmpty(failingStep.testCode),
                errorDetails,
                failingStep.stepNumber,
                escapeForPromptDisplay(failingStep.description),
                nullToEmpty(failingStep.className),
                nullToEmpty(failingStep.testClassName)
        );

        String raw = ollamaClient.generate(fixPrompt, true);
        try {
            return mapper.readValue(extractJson(raw), PlanStep.class);
        } catch (Exception e) {
            throw new RuntimeException("Could not parse fix-step JSON from model. Raw output:\n" + raw, e);
        }
    }

    private PlanResponse parsePlan(String raw) {
        try {
            return mapper.readValue(extractJson(raw), PlanResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Could not parse plan JSON from model. Raw output:\n" + raw, e);
        }
    }

    /** Strips accidental markdown fences / prose some small models still add despite instructions. */
    private String extractJson(String raw) {
        if (raw == null) throw new IllegalStateException("Null response from model");
        String trimmed = raw.trim();
        Pattern fence = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.MULTILINE);
        Matcher m = fence.matcher(trimmed);
        if (m.find()) {
            return m.group(1).trim();
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }
    private String escapeForPromptDisplay(String s) { return s == null ? "" : s.replace("\"", "'"); }
}