package com.example.newaichatapp.agent.service;

import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class LocalCommandRunner {
    public String toolVersion(String tool) {
        List<String> command = switch (tool.toLowerCase()) {
            case "jdk", "java" -> List.of("java", "-version");
            case "javac" -> List.of("javac", "-version");
            case "node" -> List.of("node", "--version");
            case "npm" -> List.of("npm.cmd", "--version");
            case "ollama" -> List.of("ollama", "--version");
            default -> throw new IllegalArgumentException("Unapproved command tool: " + tool);
        };
        Path log = null;
        try {
            log = Files.createTempFile("agent-tool-", ".log");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Tool timed out: " + tool);
            }
            String output = Files.readString(log, StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) throw new IllegalStateException(output);
            return output;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Command execution failed: " + tool, e);
        } finally {
            if (log != null) try { Files.deleteIfExists(log); } catch (Exception ignored) { }
        }
    }
}
