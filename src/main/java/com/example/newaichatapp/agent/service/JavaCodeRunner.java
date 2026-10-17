package com.example.newaichatapp.agent.service;

import com.example.newaichatapp.agent.config.AgentConfig;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class JavaCodeRunner {

    /** Result of a compilation. */
    public static class CompileResult {
        public boolean success;
        public String diagnostics;
        public Path classOutputDir;
    }

    /** Result of running a compiled class. */
    public static class RunResult {
        public boolean success;
        public int exitCode;
        public String stdout;
        public String stderr;
        public boolean timedOut;
    }

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    public JavaCodeRunner() {
        if (compiler == null) {
            throw new IllegalStateException(
                    "No system Java compiler found. Run this app with a full JDK (8-25), not a JRE, " +
                            "as the project SDK in IntelliJ.");
        }
    }

    /** Writes source to <workDir>/<className>.java, compiles it, returns the output dir with .class files. */
    public CompileResult compile(String sourceCode, String className, Path workDir) {
        CompileResult result = new CompileResult();
        try {
            Files.createDirectories(workDir);
            Path sourceFile = workDir.resolve(className + ".java");
            Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);

            StringWriter diagnosticsWriter = new StringWriter();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            try (StandardJavaFileManager fileManager =
                         compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(workDir.toFile()));
                Iterable<? extends JavaFileObject> units =
                        fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));

                JavaCompiler.CompilationTask task = compiler.getTask(
                        diagnosticsWriter, fileManager, diagnostics, null, null, units);

                boolean ok = task.call();
                result.success = ok;
                result.classOutputDir = workDir;

                StringBuilder diagText = new StringBuilder();
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    diagText.append(d.toString()).append("\n");
                }
                diagText.append(diagnosticsWriter);
                result.diagnostics = diagText.toString();
            }
        } catch (Exception e) {
            result.success = false;
            result.diagnostics = "Compilation infrastructure error: " + e;
        }
        return result;
    }

    /** Runs the compiled class's main() in a separate JVM process so it can't crash/hang the agent itself. */
    public RunResult run(Path classOutputDir, String className) {
        RunResult result = new RunResult();
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", classOutputDir.toAbsolutePath().toString(), className);
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            boolean finished = process.waitFor(AgentConfig.PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                result.timedOut = true;
                result.success = false;
                result.stderr = "Process timed out after " + AgentConfig.PROCESS_TIMEOUT_SECONDS + "s";
                return result;
            }
            result.exitCode = process.exitValue();
            result.stdout = stdout;
            result.stderr = stderr;
            result.success = result.exitCode == 0;
        } catch (Exception e) {
            result.success = false;
            result.stderr = "Process execution error: " + e;
        }
        return result;
    }

    private String readStream(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
}