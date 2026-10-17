package com.example.newaichatapp.agent.controller;

import com.example.newaichatapp.agent.*;
import com.example.newaichatapp.agent.config.AgentConfig;
import com.example.newaichatapp.agent.model.ModelActionRequest;
import com.example.newaichatapp.agent.model.ModelActionResponse;
import com.example.newaichatapp.agent.model.OllamaModelInfo;
import com.example.newaichatapp.agent.service.OllamaModelService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/models")
@CrossOrigin(origins = "*")
public class ModelController {

    private final OllamaModelService modelService;
    private final OllamaClient ollamaClient;

    public ModelController(OllamaModelService modelService, OllamaClient ollamaClient) {
        this.modelService = modelService;
        this.ollamaClient = ollamaClient;
    }

    /** GET /api/models — lists every locally pulled model, with load state and which one is active. */
    @GetMapping
    public Map<String, Object> listModels() {
        List<OllamaModelInfo> models = modelService.listModels();
        Set<String> running = safeRunningModels();

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (OllamaModelInfo m : models) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", m.name);
            row.put("id", m.id);
            row.put("size", m.size);
            row.put("modified", m.modified);
            row.put("loaded", running.contains(m.name));
            row.put("active", m.name.equals(AgentConfig.getActiveModel()));
            enriched.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", enriched);
        result.put("activeModel", AgentConfig.getActiveModel());
        return result;
    }

    /** GET /api/models/running — just the names currently resident in memory, per `ollama ps`. */
    @GetMapping("/running")
    public Map<String, Object> running() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", safeRunningModels());
        return result;
    }

    /**
     * POST /api/models/select { "modelName": "..." } — marks a model as the active one used for all
     * future planning/fix calls, AND loads it into memory immediately (keep_alive: -1).
     */
    @PostMapping("/select")
    public ModelActionResponse select(@RequestBody ModelActionRequest request) {
        ModelActionResponse response = new ModelActionResponse();
        if (request.modelName == null || request.modelName.isBlank()) {
            response.success = false;
            response.message = "modelName is required";
            response.activeModel = AgentConfig.getActiveModel();
            return response;
        }
        try {
            ollamaClient.setModelKeepAlive(request.modelName, -1L);
            AgentConfig.setActiveModel(request.modelName);
            response.success = true;
            response.message = "Loaded and selected '" + request.modelName + "' as the active model.";
            response.activeModel = AgentConfig.getActiveModel();
        } catch (Exception e) {
            response.success = false;
            response.message = "Failed to load model: " + e.getMessage();
            response.activeModel = AgentConfig.getActiveModel();
        }
        return response;
    }

    /** POST /api/models/unload { "modelName": "..." } — evicts the model from memory (keep_alive: 0). */
    @PostMapping("/unload")
    public ModelActionResponse unload(@RequestBody ModelActionRequest request) {
        ModelActionResponse response = new ModelActionResponse();
        if (request.modelName == null || request.modelName.isBlank()) {
            response.success = false;
            response.message = "modelName is required";
            response.activeModel = AgentConfig.getActiveModel();
            return response;
        }
        try {
            ollamaClient.setModelKeepAlive(request.modelName, 0L);
            response.success = true;
            response.message = "Unloaded '" + request.modelName + "' from memory.";
            response.activeModel = AgentConfig.getActiveModel();
        } catch (Exception e) {
            response.success = false;
            response.message = "Failed to unload model: " + e.getMessage();
            response.activeModel = AgentConfig.getActiveModel();
        }
        return response;
    }

    private Set<String> safeRunningModels() {
        try {
            return modelService.runningModelNames();
        } catch (Exception e) {
            return Collections.emptySet(); // don't fail the whole listing just because `ollama ps` hiccuped
        }
    }
}