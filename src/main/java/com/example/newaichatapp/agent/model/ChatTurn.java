package com.example.newaichatapp.agent.model;

public class ChatTurn {
    /** "user" or "assistant" */
    public String role;
    public String content;

    public ChatTurn() {}

    public ChatTurn(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
