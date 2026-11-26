package qupath.ext.cidl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CancellationException;

final class MicroSamSession implements AutoCloseable {
    private static final String RESPONSE = "CI_MICROSAM\t";
    private static final Gson GSON = new Gson();

    private record Connection(Path work, Process process, BufferedReader reader, BufferedWriter writer) {}

    private final String python;
    private Connection connection;
    private boolean closed;

    MicroSamSession(String python) {
        this.python = python;
    }

    private synchronized Connection connection() throws IOException {
        if (closed)
            throw new CancellationException();
        if (connection != null)
            return connection;
        var work = Files.createTempDirectory("qupath-ci-microsam-session-");
        try {
            var process = Job.pythonProcess(python, work, "--microsam-session").start();
            connection = new Connection(work, process, process.inputReader(StandardCharsets.UTF_8),
                    process.outputWriter(StandardCharsets.UTF_8));
            return connection;
        } catch (IOException e) {
            Job.cleanup(work, line -> {});
            throw new IOException("Could not start µSAM Python. Check the executable in Advanced settings.", e);
        }
    }

    // The annotation window processes requests in order on one worker at a time.
    void predict(Job job, Map<String, Object> config) throws IOException {
        job.check();
        var worker = connection();
        worker.writer().write(GSON.toJson(config));
        worker.writer().newLine();
        worker.writer().flush();
        for (String line; (line = worker.reader().readLine()) != null;) {
            job.check();
            if (line.startsWith(RESPONSE)) {
                var response = GSON.fromJson(line.substring(RESPONSE.length()), JsonObject.class);
                if (response.has("error"))
                    throw new IOException(response.get("error").getAsString());
                if (response.has("ok") && response.get("ok").getAsBoolean())
                    return;
                throw new IOException("The µSAM worker returned an invalid response.");
            }
            job.log(line);
        }
        job.check();
        throw new IOException("The µSAM Python process stopped before returning an annotation.");
    }

    @Override
    public synchronized void close() {
        closed = true;
        var worker = connection;
        connection = null;
        if (worker == null)
            return;
        worker.process().descendants().forEach(ProcessHandle::destroyForcibly);
        worker.process().destroyForcibly();
        Thread.ofVirtual().name("CI DL µSAM cleanup").start(() -> {
            try {
                worker.process().waitFor();
                worker.reader().close();
                worker.writer().close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The process has already closed its streams.
            } finally {
                Job.cleanup(worker.work(), line -> {});
            }
        });
    }
}

