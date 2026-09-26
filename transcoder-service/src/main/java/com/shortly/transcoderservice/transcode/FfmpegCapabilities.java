package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.config.TranscoderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup preflight for the ffmpeg build.
 * <p>
 * A missing filter or encoder is otherwise discovered by the first job that needs it, which means
 * the first HDR upload, or the first 1080p upload, fails in production rather than at deploy
 * time. Refusing to start is the difference between a failed rollout and a silent, partial
 * outage.
 * <p>
 * Capabilities are probed individually with {@code ffmpeg -h <type>=<name>} and identified by
 * their help header, rather than by scraping the aligned tables that {@code -filters} and
 * {@code -encoders} print. Two reasons: the flag column in those tables is not a fixed width
 * (it is {@code " .. "} for a filter, {@code "D d"} for a demuxer, {@code "V....D"} for an
 * encoder) and has changed between ffmpeg releases, so any column- or token-based parse is
 * quietly wrong after an upgrade; and {@code ffmpeg -h} exits 0 whether or not the capability
 * exists, so the only reliable signal is the help text itself.
 * <p>
 * Also records the exact ffmpeg version, which is the first thing anyone asks about when a
 * ladder comes out different from expected.
 */
@Component
public class FfmpegCapabilities {

    private static final Logger log = LoggerFactory.getLogger(FfmpegCapabilities.class);

    /** Everything the pipeline assumes the pinned Alpine ffmpeg package provides. */
    private static final List<String> REQUIRED_FILTERS = List.of(
            "scale", "crop", "setsar", "fps", "split", "asplit", "aresample", "zscale", "tonemap");
    private static final List<String> REQUIRED_ENCODERS = List.of("libx264", "aac", "mjpeg");
    private static final List<String> REQUIRED_MUXERS = List.of("hls");
    private static final List<String> REQUIRED_DEMUXERS = List.of("lavfi");

    private final TranscoderProperties properties;

    public FfmpegCapabilities(TranscoderProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        if (!properties.verifyMediaToolchain()) {
            log.info("Media toolchain verification is disabled; skipping ffmpeg preflight");
            return;
        }

        log.info("Transcoder media toolchain: {}", firstLine(capture(List.of(
                properties.ffmpegPath(), "-version"))));

        // Insertion-ordered so the failure message lists capabilities in a stable order.
        Map<String, String> probes = new LinkedHashMap<>();
        REQUIRED_FILTERS.forEach(name -> probes.put("filter=" + name, "Filter " + name));
        REQUIRED_ENCODERS.forEach(name -> probes.put("encoder=" + name, "Encoder " + name));
        REQUIRED_MUXERS.forEach(name -> probes.put("muxer=" + name, "Muxer " + name));
        REQUIRED_DEMUXERS.forEach(name -> probes.put("demuxer=" + name, "Demuxer " + name));

        List<String> missing = new ArrayList<>();
        probes.forEach((subject, expectedHeader) -> {
            String help = capture(List.of(properties.ffmpegPath(), "-hide_banner", "-h", subject));
            if (!firstLine(help).startsWith(expectedHeader)) {
                missing.add(subject + " (got: \"" + firstLine(help) + "\")");
            }
        });

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "The ffmpeg build in this image does not provide: " + missing
                            + ". The pinned ffmpeg apk version is missing them, or the pipeline needs a"
                            + " different build. Refusing to start rather than failing individual jobs"
                            + " later, when only some uploads would break.");
        }

        // ffprobe ships in the same apk package as ffmpeg, but confirm the binary is actually
        // on PATH rather than assuming the package layout holds.
        capture(List.of(properties.ffprobePath(), "-version"));

        log.info("ffmpeg preflight passed: {} filters, {} encoders, {} muxers, {} demuxers verified",
                REQUIRED_FILTERS.size(), REQUIRED_ENCODERS.size(),
                REQUIRED_MUXERS.size(), REQUIRED_DEMUXERS.size());
    }

    private String firstLine(String output) {
        if (output == null) {
            return "";
        }
        int newline = output.indexOf('\n');
        return (newline > 0 ? output.substring(0, newline) : output).strip();
    }

    /**
     * Runs a capability query and returns its output.
     * <p>
     * A non-zero exit is only fatal when the binary could not be run at all. {@code ffmpeg -h}
     * for an unknown capability exits 0 and prints a diagnostic, which {@link #verify} reads.
     */
    private String capture(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "Timed out probing media capabilities: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "Media capability probe failed (" + String.join(" ", command)
                                + "): " + output);
            }
            return output.toString();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not execute " + String.join(" ", command)
                            + ". Is ffmpeg/ffprobe installed and on PATH?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing media capabilities", e);
        }
    }
}
