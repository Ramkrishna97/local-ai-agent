package com.example.newaichatapp.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanStep {
    public int stepNumber;
    public String description;
    public String generatedCode;      // full compilable Java source for this step
    public String className;          // public class name inside generatedCode
    public String testCode;           // full compilable Java source containing a main() that tests generatedCode
    public String testClassName;      // public class name inside testCode
    public String expectedResult;     // substring expected in test's stdout, or "EXIT_0"
    public String status = "PENDING"; // PENDING | PASSED | FAILED

    // execution trace, filled in at runtime (not requested from the LLM)
    public String compileOutput;
    public String runOutput;
    public String testRunOutput;
    public int attempts = 0;
    public String lastError;
}