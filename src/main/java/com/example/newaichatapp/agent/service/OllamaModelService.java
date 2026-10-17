package com.example.newaichatapp.agent.service;


import com.example.newaichatapp.agent.model.OllamaModelInfo;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class OllamaModelService {

    /** Runs `ollama list` as a subprocess and parses its tabular output. */
    public List<OllamaModelInfo> listModels() {
        String output = runOllamaCli("list");
        return parseListOutput(output);
    }

    /** Runs `ollama ps` to find which model(s) are currently resident in memory. */
    public Set<String> runningModelNames() {
        String output = runOllamaCli("ps");
        Set<String> names = new LinkedHashSet<>();
        String[] lines = output.split("\n");
        for (int i = 1; i < lines.length; i++) { // skip header row
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] cols = line.split("\\s{2,}");
            if (cols.length > 0 && !cols[0].isBlank()) {
                names.add(cols[0].trim());
            }
        }
        return names;
    }

    /** Parses the whitespace-aligned columns of `ollama list`: NAME  ID  SIZE  MODIFIED. */
    private List<OllamaModelInfo> parseListOutput(String output) {
        List<OllamaModelInfo> models = new ArrayList<>();
        String[] lines = output.split("\n");
        if (lines.length == 0) return models;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.trim().isEmpty()) continue;

            String[] cols = line.trim().split("\\s{2,}");
            if (cols.length < 3) continue;

            OllamaModelInfo info = new OllamaModelInfo();
            info.name = cols[0].trim();
            info.id = cols.length > 1 ? cols[1].trim() : "";
            info.size = cols.length > 2 ? cols[2].trim() : "";
            info.modified = cols.length > 3
                    ? String.join(" ", Arrays.copyOfRange(cols, 3, cols.length)).trim()
                    : "";
            models.add(info);
        }
        return models;
    }

    private String runOllamaCli(String... args) {
        List<String> command = new ArrayList<>();
        command.add("ollama");
        command.addAll(Arrays.asList(args));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                output = sb.toString();
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("`ollama " + String.join(" ", args) + "` timed out after 30s");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("`ollama " + String.join(" ", args) + "` failed:\n" + output);
            }
            return output;
        } catch (IOException e) {
            throw new RuntimeException(
                    "Could not run the 'ollama' CLI. Is Ollama installed and on PATH? " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running 'ollama " + String.join(" ", args) + "'", e);
        }
    }
}