package com.example.newaichatapp.agent.model;

import com.example.newaichatapp.agent.model.PlanStep;

import java.util.List;

public class ExecuteResponse {
    public String goal;
    public String answer;
    public String taskType;
    public boolean overallSuccess;
    public List<PlanStep> steps;
    public String message;
}