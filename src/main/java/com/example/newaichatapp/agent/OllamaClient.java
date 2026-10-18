package com.example.newaichatapp.agent;

import com.example.newaichatapp.agent.config.AgentConfig;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;

import com.example.newaichatapp.agent.model.*;
import com.example.newaichatapp.agent.service.*;
import com.example.newaichatapp.agent.*;

@Service
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    /** Substring Ollama uses in its error body when a local model's generation loop gets killed. */
    private static final String REPEAT_LIMIT_MARKER = "token repeat limit reached";

    private final ConcurrentHashMap<Thread, HttpURLConnection> active = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(AgentConfig.OLLAMA_READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    public void cancel(Thread worker) {
        HttpURLConnection connection = active.get(worker);
        if (connection != null) connection.disconnect();
    }

    /** Calls POST /api/generate (blocking) and returns the raw "response" text from Ollama. */
    public String generate(String prompt, boolean forceJson) {
        try {
            return generateOnce(prompt, forceJson);
        } catch (RepeatLimitException repeatEx) {
            // The model looped on itself and Ollama aborted generation. Retrying with the identical
            // prompt tends to loop again, so we retry once with a visibly shorter prompt asking for a
            // terser answer, which empirically reduces repeat-loop risk on small/quantized models.
            String model = AgentConfig.getActiveModel();
            log.warn("Ollama repeat-limit hit for model={}, retrying once with a shortened prompt", model);
            String shortened = shortenForRetry(prompt);
            try {
                return generateOnce(shortened, forceJson);
            } catch (RepeatLimitException secondFailure) {
                log.error("Ollama repeat-limit hit again on retry for model={}; giving up", model);
                throw new IllegalStateException(
                        "The local model got stuck repeating itself twice in a row (Ollama's repeat-limit guard). " +
                        "This is usually caused by a very small/quantized model struggling with a long or complex " +
                        "prompt. Try a smaller task, a bigger model, or increasing repeat_penalty further.", secondFailure);
            }
        }
    }

    private String generateOnce(String prompt, boolean forceJson) {
        String model = AgentConfig.getActiveModel();
        if (model == null || model.isBlank()) throw new IllegalStateException("Select an Ollama model first");
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Run cancelled");
        HttpURLConnection connection = null;
        long start = System.nanoTime();
        try {
            connection = (HttpURLConnection) new URL(AgentConfig.OLLAMA_BASE_URL + "/api/generate").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(AgentConfig.OLLAMA_READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            active.put(Thread.currentThread(), connection);
            byte[] body = mapper.writeValueAsString(new OllamaRequest(model, prompt, forceJson)).getBytes(StandardCharsets.UTF_8);
            log.info("Ollama generate request model={} json={} promptChars={}", model, forceJson, prompt.length());
            try (var output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response;
            try (stream) { response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
            if (status >= 400) {
                if (response != null && response.contains(REPEAT_LIMIT_MARKER)) {
                    log.warn("Ollama HTTP {} repeat-limit marker detected model={}", status, model);
                    throw new RepeatLimitException(response);
                }
                log.error("Ollama HTTP {} model={} body={}", status, model, response);
                throw new IllegalStateException("Ollama HTTP " + status + ": " + response);
            }
            OllamaResponse parsed = mapper.readValue(response, OllamaResponse.class);
            if (parsed == null || parsed.response == null || parsed.response.isBlank()) {
                log.warn("Ollama returned blank response text model={}", model);
                throw new IllegalStateException("Ollama returned empty response text; check model output/context length");
            }
            log.info("Ollama generate OK model={} responseChars={} elapsedMs={}",
                    model, parsed.response.length(), (System.nanoTime() - start) / 1_000_000);
            return parsed.response;
        } catch (RepeatLimitException rle) {
            throw rle;
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Run cancelled", ex);
            log.error("Ollama request failed model={}", model, ex);
            throw new IllegalStateException("Ollama request failed: " + ex.getMessage(), ex);
        } finally {
            active.remove(Thread.currentThread());
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Streams POST /api/generate with stream:true, invoking onToken(chunk) for every partial piece of
     * text Ollama emits (NDJSON, one JSON object per line, each with a "response" fragment and a "done"
     * flag). Used by chat mode so the browser sees text appear token-by-token instead of waiting for
     * the whole reply. Returns the full concatenated response text once the stream completes.
     */
    public String generateStreaming(String prompt, Consumer<String> onToken) {
        String model = AgentConfig.getActiveModel();
        if (model == null || model.isBlank()) throw new IllegalStateException("Select an Ollama model first");
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Run cancelled");

        HttpURLConnection connection = null;
        long start = System.nanoTime();
        StringBuilder full = new StringBuilder();
        try {
            connection = (HttpURLConnection) new URL(AgentConfig.OLLAMA_BASE_URL + "/api/generate").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(AgentConfig.OLLAMA_READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            active.put(Thread.currentThread(), connection);

            byte[] body = mapper.writeValueAsString(new OllamaRequest(model, prompt, false, true)).getBytes(StandardCharsets.UTF_8);
            log.info("Ollama chat stream request model={} promptChars={}", model, prompt.length());
            try (var output = connection.getOutputStream()) { output.write(body); }

            int status = connection.getResponseCode();
            if (status >= 400) {
                String errBody;
                try (InputStream err = connection.getErrorStream()) {
                    errBody = err == null ? "" : new String(err.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.error("Ollama chat stream HTTP {} model={} body={}", status, model, errBody);
                if (errBody.contains(REPEAT_LIMIT_MARKER)) {
                    throw new RepeatLimitException(errBody);
                }
                throw new IllegalStateException("Ollama HTTP " + status + ": " + errBody);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Run cancelled");
                    if (line.isBlank()) continue;
                    OllamaResponse chunk;
                    try {
                        chunk = mapper.readValue(line, OllamaResponse.class);
                    } catch (Exception parseEx) {
                        log.warn("Ollama chat stream: skipping unparseable NDJSON line model={}", model);
                        continue;
                    }
                    if (chunk == null) continue;
                    if (chunk.response != null && !chunk.response.isEmpty()) {
                        full.append(chunk.response);
                        try {
                            onToken.accept(chunk.response);
                        } catch (Exception sinkEx) {
                            // a broken/closed downstream consumer shouldn't abort reading the rest of
                            // the stream from Ollama; just stop forwarding tokens
                        }
                    }
                    if (chunk.done) break;
                }
            }

            log.info("Ollama chat stream OK model={} responseChars={} elapsedMs={}",
                    model, full.length(), (System.nanoTime() - start) / 1_000_000);
            if (full.length() == 0) {
                log.warn("Ollama chat stream returned no text model={}", model);
            }
            return full.toString();
        } catch (RepeatLimitException rle) {
            log.warn("Ollama chat stream repeat-limit hit model={}", model);
            throw new IllegalStateException(
                    "The local model got stuck repeating itself and Ollama aborted the response. " +
                    "Try asking again, more briefly, or switch to a less aggressively quantized model.", rle);
        } catch (Exception ex) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Run cancelled", ex);
            log.error("Ollama chat stream failed model={}", model, ex);
            throw new IllegalStateException("Ollama request failed: " + ex.getMessage(), ex);
        } finally {
            active.remove(Thread.currentThread());
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Loads a model into memory (or unloads it) without generating any real content, by calling
     * /api/generate with an empty prompt and a keep_alive directive.
     *
     * keepAliveSeconds = -1  => keep resident until explicitly unloaded
     * keepAliveSeconds =  0  => unload from memory immediately
     * keepAliveSeconds >  0  => keep resident for that many seconds of inactivity
     *
     * (JSON number is used instead of a Go duration string so we don't trip the "missing unit" parser.)
     */
    public void setModelKeepAlive(String modelName, long keepAliveSeconds) {
        String url = AgentConfig.OLLAMA_BASE_URL + "/api/generate";
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("prompt", "");
        body.put("stream", false);
        body.put("keep_alive", keepAliveSeconds);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        SimpleClientHttpRequestFactory loadFactory = new SimpleClientHttpRequestFactory();
        loadFactory.setConnectTimeout(10_000);
        loadFactory.setReadTimeout(AgentConfig.OLLAMA_LOAD_TIMEOUT_MS);
        RestTemplate loadTemplate = new RestTemplate(loadFactory);

        log.info("Ollama keep_alive model={} seconds={}", modelName, keepAliveSeconds);
        try {
            loadTemplate.postForEntity(URI.create(url), entity, String.class);
            log.info("Ollama keep_alive OK model={} seconds={}", modelName, keepAliveSeconds);
        } catch (Exception e) {
            log.error("Ollama keep_alive failed model={} seconds={}", modelName, keepAliveSeconds, e);
            throw new RuntimeException("Failed to set keep_alive for model '" + modelName +
                    "' at " + url + ": " + e.getMessage(), e);
        }
    }

    /** Shortens an overly long prompt for the repeat-limit retry: keeps the instructions, trims bulky context. */
    private String shortenForRetry(String prompt) {
        if (prompt == null) return "";
        int maxLen = 2500;
        if (prompt.length() <= maxLen) {
            return prompt + "\n\nKeep your answer concise and avoid repeating yourself.";
        }
        // keep the first chunk (usually instructions/format spec) and the last chunk (usually the
        // actual user request), dropping the middle (usually bulky "previous context" filler)
        int headLen = 1500;
        int tailLen = 800;
        String head = prompt.substring(0, Math.min(headLen, prompt.length()));
        String tail = prompt.length() > tailLen ? prompt.substring(prompt.length() - tailLen) : prompt;
        return head + "\n\n[...context shortened to reduce repetition risk...]\n\n" + tail +
                "\n\nKeep your answer concise and avoid repeating yourself.";
    }

    /** Internal marker exception for Ollama's "prediction aborted, token repeat limit reached" failure. */
    private static class RepeatLimitException extends RuntimeException {
        RepeatLimitException(String body) { super(body); }
    }
}
