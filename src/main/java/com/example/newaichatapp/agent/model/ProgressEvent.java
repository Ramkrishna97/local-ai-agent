package com.example.newaichatapp.agent.model;

/**
 * Pushed live over SSE so the UI isn't a static screen.
 */
public class ProgressEvent {
    public String type;       // PLANNING | PLAN_READY | STEP_START | STEP_PHASE | STEP_RETRY | STEP_RESULT | DONE | ERROR
    public Integer stepNumber;
    public String stepDescription;
    public String phase;      // "compiling", "running", "testing", "requesting fix", ...
    public String message;    // short human-readable line for a live log
    public String status;     // PENDING | RUNNING | PASSED | FAILED
    public Integer attempt;
    public PlanResponse plan;           // present on PLAN_READY
    public PlanStep step;               // present on STEP_RESULT
    public ExecuteResponse finalResult; // present on DONE / ERROR

    public static ProgressEvent of(String type, String message) {
        ProgressEvent e = new ProgressEvent();
        e.type = type;
        e.message = message;
        return e;
    }
}