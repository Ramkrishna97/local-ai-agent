package com.example.newaichatapp.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanResponse {
    public String goal;
    public String answer;
    public String taskType;
    public List<PlanStep> steps = new ArrayList<>();
    public String end = "END";
}