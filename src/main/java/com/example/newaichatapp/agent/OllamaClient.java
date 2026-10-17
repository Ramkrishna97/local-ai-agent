package com.example.newaichatapp.agent;


import com.example.newaichatapp.agent.config.AgentConfig;
import com.example.newaichatapp.agent.model.OllamaRequest;
import com.example.newaichatapp.agent.model.OllamaResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(AgentConfig.OLLAMA_READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    /** Calls POST /api/generate and returns the raw "response" text from Ollama. */
    public String generate(String prompt, boolean forceJson) {
        String model = AgentConfig.getActiveModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "No model selected. Open the Models panel in the UI and select a model first " +
                            "(or start the app with -Dollama.model=<name>).");
        }

        String url = AgentConfig.OLLAMA_BASE_URL + "/api/generate";
        OllamaRequest body = new OllamaRequest(model, prompt, forceJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<OllamaRequest> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> raw = restTemplate.postForEntity(URI.create(url), entity, String.class);
            if (raw.getBody() == null) {
                throw new IllegalStateException("Empty response body from Ollama at " + url);
            }
            OllamaResponse parsed = mapper.readValue(raw.getBody(), OllamaResponse.class);
            return parsed.response;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            throw new RuntimeException("Model '" + model + "' is not pulled in Ollama. Run `ollama pull " +
                    model + "`, or select a different model from the Models panel.", nf);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Ollama at " + url +
                    " (is it running? model '" + model + "' pulled?): " + e.getMessage(), e);
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

        try {
            loadTemplate.postForEntity(URI.create(url), entity, String.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set keep_alive for model '" + modelName +
                    "' at " + url + ": " + e.getMessage(), e);
        }
    }
}