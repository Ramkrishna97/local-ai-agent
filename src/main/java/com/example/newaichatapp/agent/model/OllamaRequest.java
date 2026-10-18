package com.example.newaichatapp.agent.model;

import java.util.HashMap;
import java.util.Map;

public class OllamaRequest {
    public String model;
    public String prompt;
    public boolean stream = false;
    public Boolean think;
    public String format; // "json" to force structured output
    public Map<String, Object> options;

    public OllamaRequest(String model, String prompt, boolean forceJson) {
        this(model, prompt, forceJson, false);
    }

    /**
     * @param stream when true, Ollama returns NDJSON (one partial-token JSON object per line) instead
     *               of a single blocking response; used by chat mode for real-time token streaming.
     */
    public OllamaRequest(String model, String prompt, boolean forceJson, boolean stream) {
        this.model = model;
        if (model != null && model.toLowerCase().startsWith("qwen3")) this.think = false;
        this.prompt = prompt;
        this.stream = stream;
        if (forceJson) this.format = "json";
        this.options = defaultOptions();
    }

    /**
     * Generation options tuned to reduce the "prediction aborted, token repeat limit reached" failure
     * that small/quantized local models are prone to: a repeat_penalty actively discourages the model
     * from looping on the same tokens, repeat_last_n widens the window it looks back over to judge
     * repetition, and a slightly-above-zero temperature with top_p/top_k keeps token choice from
     * collapsing onto a single repeating path while still staying deterministic-ish for JSON output.
     */
    public static Map<String, Object> defaultOptions() {
        Map<String, Object> opts = new HashMap<>();
        opts.put("temperature", 0.2);
        opts.put("repeat_penalty", 1.3);
        opts.put("repeat_last_n", 256);
        opts.put("top_p", 0.9);
        opts.put("top_k", 40);
        return opts;
    }
}
