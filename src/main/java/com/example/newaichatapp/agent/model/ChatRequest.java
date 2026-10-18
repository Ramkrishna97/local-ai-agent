package com.example.newaichatapp.agent.model;

public class ChatRequest {
    public String prompt;
    /** Optional prior turns, oldest first, so the model has conversational context. May be null/empty. */
    public java.util.List<ChatTurn> history;
}
