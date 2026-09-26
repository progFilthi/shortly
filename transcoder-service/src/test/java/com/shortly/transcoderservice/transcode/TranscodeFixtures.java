package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.config.TranscoderProperties.Rendition;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/**
 * Test fixtures for the transcode layer.
 * <p>
 * {@link TranscoderProperties} is a record with twenty-one components, so every construction
 * site has to name all of them positionally. Centralising that here means adding a component
 * breaks one file with a compiler error, rather than silently reordering arguments across a
 * dozen tests. Tests should call {@link #productionDefaults()} and, when they need to vary
 * something, the specific named factory for that variation.
 */
final class TranscodeFixtures {

    static final DataSize FIVE_HUNDRED_MB = DataSize.ofMegabytes(500);

    private TranscodeFixtures() {
    }

    /**
     * Production defaults: the built-in 6-rung 9:16 ladder, 30fps output, 2s segments,
     * veryfast x264, AAC-LC stereo, 10x10 sprite sheet, preflight verification on.
     */
    static TranscoderProperties productionDefaults() {
        return build(30, Duration.ofMinutes(10), "/work", true);
    }

    static TranscoderProperties withFps(int fps) {
        return build(fps, Duration.ofMinutes(10), "/work", true);
    }

    /** Production defaults with a shorter job budget, for tests that should not hang. */
    static TranscoderProperties withJobTimeout(Duration timeout) {
        return build(30, timeout, "/work", true);
    }

    /** Production defaults with the scratch directory pointed at a test's temp dir. */
    static TranscoderProperties withWorkDir(String workDir) {
        return build(30, Duration.ofMinutes(5), workDir, true);
    }

    private static TranscoderProperties build(int fps, Duration jobTimeout, String workDir,
                                              boolean verifyToolchain) {
        return new TranscoderProperties(
                /* maxDuration          */ Duration.ofSeconds(60),
                /* maxUploadBytes       */ FIVE_HUNDRED_MB,
                /* maxSourceWidth       */ 3840,
                /* maxSourceHeight      */ 2160,
                /* ladder               */ null,   // null => TranscoderProperties.defaultLadder()
                /* outputFps            */ fps,
                /* segmentSeconds       */ 2,
                /* audioBitrate         */ "128k",
                /* audioSampleRate      */ 48000,
                /* audioChannels        */ 2,
                /* x264Preset           */ "veryfast",
                /* x264Profile          */ "main",
                /* ffmpegPath           */ "ffmpeg",
                /* ffprobePath          */ "ffprobe",
                /* jobTimeout           */ jobTimeout,
                /* workDir              */ workDir,
                /* uploadConcurrency    */ 24,
                /* uploadTimeout        */ Duration.ofMinutes(4),
                /* spriteColumns        */ 10,
                /* spriteRows           */ 10,
                /* spriteTileWidth      */ 240,
                /* spriteJpegQuality    */ 3,
                /* includeFfmpegStderr  */ false,
                /* verifyMediaToolchain */ verifyToolchain);
    }

    /** A three-rung ladder spanning 1080x1920 down to 180x320. */
    static List<Rendition> threeRungs() {
        return List.of(
                Rendition.of("v0", 1080, 1920, 4500),
                Rendition.of("v1", 540, 960, 1100),
                Rendition.of("v2", 180, 320, 150));
    }
}
