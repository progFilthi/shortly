package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.config.TranscoderProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs ffmpeg as a supervised child process.
 *
 * <p>Shelling out has three failure modes a pure-Java pipeline does not, and all three are
 * handled here: a runaway job (hard wall-clock budget plus SIGKILL), a child orphaned across a
 * container shutdown (every process is tracked and killed on the way out), and a deadlocked
 * stderr pipe (drained on a dedicated thread for the process's whole life, not just until exit).
 */
@Component
public class FfmpegExecutor {

    private static final Logger log = LoggerFactory.getLogger(FfmpegExecutor.class);

    private final TranscoderProperties properties;
    private final Set<Process> running = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ffmpeg-watchdog");
                t.setDaemon(true);
                return t;
            });

    public FfmpegExecutor(TranscoderProperties properties) {
        this.properties = properties;
    }

    /**
     * Executes a command and blocks until it finishes, fails, or exceeds its budget.
     *
     * @return the exit code and captured stderr; a non-zero exit is NOT an exception here,
     *         because the caller usually wants to classify it and attach context first
     * @throws FfmpegTimeoutException  if the wall-clock budget elapsed
     * @throws FfmpegExecutionException if the process could not be started at all
     */
    public Result run(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new FfmpegExecutionException(
                    "Could not start " + command.get(0) + ": " + e.getMessage(), e);
        }

        running.add(process);
        ScheduledFuture<?> killSwitch = scheduleKill(process, timeout, command);

        // Drained for the process's whole life; this capture is the only record of why a job
        // actually failed.
        StringBuilder stderr = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderr) {
                        // Bound the buffer: a pathological input can emit megabytes of
                        // warnings, and we must not hold that in memory or put it in a log.
                        if (stderr.length() < MAX_STDERR_CHARS) {
                            stderr.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("stderr drain ended early for {}", command.get(0), e);
            }
        }, "ffmpeg-stderr-" + process.pid());
        drainer.setDaemon(true);
        drainer.start();

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                killSwitch.cancel(false);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new FfmpegTimeoutException(
                        "ffmpeg exceeded its " + timeout.toSeconds() + "s budget for "
                                + describeTarget(command) + "; last output: " + tail(stderr));
            }
            return new Result(process.exitValue(), tail(stderr));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new FfmpegExecutionException("Interrupted while waiting for ffmpeg", e);
        } finally {
            killSwitch.cancel(false);
            running.remove(process);
            drainer.interrupt();
        }
    }

    /**
     * Outcome of one ffmpeg invocation.
     *
     * @param exitCode ffmpeg's exit status
     * @param stderr   the tail of ffmpeg's diagnostics, always populated on failure
     */
    public record Result(int exitCode, String stderr) {

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    private static final int MAX_STDERR_CHARS = 16_000;

    private ScheduledFuture<?> scheduleKill(Process process, Duration timeout, List<String> command) {
        return watchdog.schedule(() -> {
            if (process.isAlive()) {
                log.error("ffmpeg exceeded its {}s budget, killing pid {} for {}",
                        timeout.toSeconds(), process.pid(), describeTarget(command));
                process.destroyForcibly();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Frees the container's writable layer on shutdown. Called by the shutdown hook so a
     * rolling deploy does not leave a multi-gigabyte encoder still running while the new
     * container starts.
     */
    @PreDestroy
    public void shutdown() {
        if (running.isEmpty()) {
            return;
        }
        log.warn("Shutting down with {} ffmpeg process(es) still running; terminating", running.size());
        for (Process process : List.copyOf(running)) {
            process.destroy();
        }
        // Give ffmpeg a moment to close its output files cleanly, then insist.
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Process process : List.copyOf(running)) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        watchdog.shutdownNow();
    }

    private String tail(StringBuilder buffer) {
        synchronized (buffer) {
            String all = buffer.toString();
            return all.length() <= 500 ? all : all.substring(all.length() - 500);
        }
    }

    /** Best-effort identification of what was being encoded, for logs and error messages. */
    private String describeTarget(List<String> command) {
        for (String arg : command) {
            if (arg.endsWith(".mp4") || arg.endsWith(".mov") || arg.endsWith(".m4v")) {
                Path path = Path.of(arg);
                return path.getFileName().toString();
            }
        }
        return String.join(" ", command.subList(0, Math.min(6, command.size())));
    }
}
