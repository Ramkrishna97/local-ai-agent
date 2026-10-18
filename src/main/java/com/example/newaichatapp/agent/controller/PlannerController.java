package com.example.newaichatapp.agent.controller;

import com.example.newaichatapp.agent.StepExecutor;
import com.example.newaichatapp.agent.model.*;
import com.example.newaichatapp.agent.service.PlannerService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import com.example.newaichatapp.agent.OllamaClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/plan")
@CrossOrigin(origins = "*")
public class PlannerController {
    private static final Logger log = LoggerFactory.getLogger(PlannerController.class);
    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    private final OllamaClient ollamaClient;
    private final PlannerService planner;
    private final StepExecutor executor;
    private final TaskExecutor workers;

    public PlannerController(PlannerService planner, StepExecutor executor,
                             @Qualifier("agentTaskExecutor") TaskExecutor workers, OllamaClient ollamaClient) {
        this.planner = planner; this.executor = executor; this.workers = workers; this.ollamaClient = ollamaClient;
    }

    @PostMapping("/plan-only")
    public PlanResponse planOnly(@RequestBody ExecuteRequest request) { return planner.createPlan(request.prompt); }

    @PostMapping("/execute")
    public ExecuteResponse execute(@RequestBody ExecuteRequest request) {
        PlanResponse plan = planner.createPlan(request.prompt);
        return finish(plan, executor.executeAll(plan, null));
    }

    @PostMapping("/stop/{runId}")
    public Map<String, Object> stop(@PathVariable String runId) {
        Thread worker = running.get(runId);
        if (worker == null) return Map.of("stopped", false, "message", "Run not found or already completed");
        log.info("Cancellation requested runId={}", runId);
        worker.interrupt();
        ollamaClient.cancel(worker);
        return Map.of("stopped", true);
    }

    @GetMapping(value = "/execute-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeStream(@RequestParam String prompt, @RequestParam String runId) {
        SseEmitter emitter = new SseEmitter(0L);
        workers.execute(() -> {
            running.put(runId, Thread.currentThread());
            log.info("Agent run started id={} prompt={}", runId, prompt);
            try {
                send(emitter, ProgressEvent.of("PLANNING", "Creating a plan..."));
                PlanResponse plan = planner.createPlan(prompt);
                ProgressEvent ready = ProgressEvent.of("PLAN_READY", "Plan ready: " + plan.steps.size() + " step(s)");
                ready.plan = plan; send(emitter, ready);
                boolean success = executor.executeAll(plan, e -> send(emitter, e));
                ExecuteResponse response = finish(plan, success);
                ProgressEvent done = ProgressEvent.of("DONE", response.message);
                done.finalResult = response;
                send(emitter, done);
                log.info("Agent run complete id={} success={}", runId, success);
                emitter.complete();
            } catch (Exception ex) {
                log.error("Agent run failed id={}", runId, ex);
                ProgressEvent error = ProgressEvent.of("ERROR", ex.getMessage());
                send(emitter, error); emitter.complete();
            } finally { running.remove(runId); }
        });
        return emitter;
    }

    private ExecuteResponse finish(PlanResponse plan, boolean success) {
        ExecuteResponse response = new ExecuteResponse();
        response.goal = plan.goal; response.steps = plan.steps; response.overallSuccess = success;
        response.taskType = plan.steps.size() == 1 ? plan.steps.get(0).type : "PLAN";
        if ("DIRECT".equals(response.taskType)) response.answer = plan.steps.get(0).runOutput;
        response.message = success ? "All steps completed successfully." : "One or more steps failed validation.";
        return response;
    }

    private void send(SseEmitter emitter, ProgressEvent event) {
        try { emitter.send(SseEmitter.event().name("progress").data(event, MediaType.APPLICATION_JSON)); }
        catch (Exception ignored) { }
    }
}
