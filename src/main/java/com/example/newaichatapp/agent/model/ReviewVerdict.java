package com.example.newaichatapp.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewVerdict {
    public boolean matches;
    public String reasoning;
    public String improvements;
}
