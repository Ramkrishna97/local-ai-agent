# Planner Agent V4 integration overlay

Copy `src/main/` into your Spring Boot project, keeping your existing build files and application main class. The static `src/main/resources/static/index.html` is automatically served at `http://localhost:8080/` by Spring Boot MVC unless custom routing/security overrides it. `/app` redirects to `/index.html`. Starting Spring Boot does not automatically open a browser window; that would require OS/browser launch configuration.

## Changes

- Run/Stop controls. Stop calls `POST /api/plan/stop/{runId}`; interrupts the current worker, disconnects the active Ollama HTTP request, and prevents later steps. Stop is best effort: cancellation cannot undo generated output or guarantee that a local model immediately stops GPU work.
- Resizable plan sidebar and activity panel, resizable input; responsive mobile fallback.
- Sort Ollama models by name A/Z or parsed display size ascending/descending.
- Startup active model is null. This prevents **automatic selection**; it does not unload models already resident in an external Ollama process. Loading and selecting from the UI still works. Do not indiscriminately unload models that another app could be using.
- Backend SLF4J records agent request, run state, Ollama requests, duration and stack traces. Avoid entering secrets in prompts while debug logging is enabled.
- Plan JSON parsing retries once after invalid JSON. Empty Ollama response now reports a specific error. `think=false` is sent for qwen3 family models, to avoid consuming output budget in internal reasoning on JSON-only requests. If your Ollama/model version rejects that field, remove it or update Ollama.
- New PROJECT plan type: model emits JSON mapping relative file paths to complete code; backend writes those files into an isolated temporary workspace, and UI displays a file selector with individual code files. Unlike a standalone Java class, Spring Boot and React sources are not compiled with bare `javac`; you must build projects with their proper Gradle/Maven/npm dependencies.

## Known limitations

This is a source overlay, not a complete standalone Gradle repository. It has **not** been end-to-end tested with your local Ollama or project dependency graph. The agent still uses subjective AI reviews; passed does not prove build, runtime correctness, API integration, or completeness. The generated project files are created in a temp directory and may be lost during OS cleanup; persist/export them before relying on them. Multi-file generation is subject to model context limits; small models may omit files. Arbitrary shell commands are not enabled. Generated source should not be run outside an isolated sandbox.

## Checklist

1. Back up existing source, overlay this `src/main/`, restart Spring Boot.
2. Visit `http://localhost:8080/` (or `/index.html`). Verify `No model selected` on restart.
3. Select the desired model from Models. Test model sorting.
4. Run a short one-step prompt and press Stop during generation. Watch Spring Boot logs.
5. For Spring Boot/React requests, expect `PROJECT` steps and a file dropdown. They are not verified builds without a project-aware build runner.
6. Check `ollama --version`, `ollama ps`, and PowerShell generation outside the app if empty JSON persists.
