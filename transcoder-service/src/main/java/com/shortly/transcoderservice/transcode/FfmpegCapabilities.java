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

/** Startup preflight for the ffmpeg build. A missing filter or encoder is otherwise discovered by
 * in production rather than at deploy time. */
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

        // ffprobe ships in the same apk package as ffmpeg, but confirm the binary is actually on PATH
        // rather than assuming the package layout holds.
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

    /** Runs a capability query and returns its output. A non-zero exit is only fatal when the binary
     * diagnostic, which {@link #verify} reads. */
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
