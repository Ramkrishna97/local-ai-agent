package com.example.newaichatapp.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanStep {
    public int stepNumber;
    public String description;
    public String type = "JAVA";
    public String acceptanceCriteria;
    public String generatedCode;
    public java.util.Map<String, String> files;
    public String className;
    public String testCode;
    public String testClassName;
    public String expectedResult;
    public String status = "PENDING";
    public String compileOutput;
    public String runOutput;
    public String testRunOutput;
    public String lastError;
    public String reviewReasoning;
    public Boolean reviewPassed;
    public int attempts;
}
