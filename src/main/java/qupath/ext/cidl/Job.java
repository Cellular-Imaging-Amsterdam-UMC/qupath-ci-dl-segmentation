package qupath.ext.cidl;

import com.google.gson.Gson;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

final class Job {
    private final Consumer<String> output;
    private volatile boolean cancelled;
    private Process process;

    Job(Consumer<String> output) {
        this.output = output;
    }

    void log(String line) {
        output.accept(line);
    }

    void check() {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new CancellationException();
    }

    boolean isCancelled() {
        return cancelled;
    }

    synchronized void cancel() {
        cancelled = true;
        if (process != null)
            terminate(process);
    }

    private static void terminate(Process process) {
        var descendants = process.descendants().toList();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    void python(String executable, Path work, Map<String, Object> config) throws IOException, InterruptedException {
        check();
        var script = work.resolve("backend.py");
        try (var source = Job.class.getResourceAsStream("backend.py")) {
            if (source == null)
                throw new IOException("The Python backend is missing from the extension JAR.");
            Files.copy(source, script);
        }
        var manifest = work.resolve("job.json");
        Files.writeString(manifest, new Gson().toJson(config));
        var builder = new ProcessBuilder(executable, "-u", script.toString(), manifest.toString());
        builder.directory(work.toFile()).redirectErrorStream(true);
        var env = builder.environment();
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUNBUFFERED", "1");
        if (System.getProperty("os.name").startsWith("Windows") && !executable.equals("python")) {
            var python = Path.of(executable).getParent();
            var prefix = python.getFileName().toString().equalsIgnoreCase("Scripts") ? python.getParent() : python;
            var entries = List.of(python.toString(), prefix.resolve("Library/bin").toString(),
                    prefix.resolve("Scripts").toString(), env.getOrDefault("PATH", ""));
            env.put("PATH", String.join(java.io.File.pathSeparator, entries));
        }
        Process started;
        synchronized (this) {
            check();
            try {
                started = builder.start();
            } catch (IOException e) {
                throw new IOException("Could not start Python. Select the interpreter for this model. " + e.getMessage(), e);
            }
            process = started;
        }
        try {
            try (var reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null && !cancelled; )
                    log(line);
            }
            int code = started.waitFor();
            check();
            if (code != 0)
                throw new IOException("Python exited with code " + code + ". See the log above.");
        } finally {
            synchronized (this) {
                if (started.isAlive())
                    terminate(started);
                process = null;
            }
        }
    }

    static void cleanup(Path directory, Consumer<String> log) {
        try (var files = Files.walk(directory)) {
            for (var path : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        } catch (IOException e) {
            log.accept("Could not remove temporary files at " + directory);
        }
    }

    void apply(Runnable operation) throws Exception {
        check();
        var result = new java.util.concurrent.FutureTask<Void>(() -> {
            check();
            operation.run();
            return null;
        });
        Platform.runLater(result);
        try {
            result.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception exception)
                throw exception;
            if (e.getCause() instanceof Error error)
                throw error;
            throw e;
        }
    }
}
