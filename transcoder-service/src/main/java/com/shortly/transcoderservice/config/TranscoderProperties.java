package com.shortly.transcoderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/** Externalised configuration. */
@ConfigurationProperties(prefix = "transcoder")
public record TranscoderProperties(

        /* ------------------------------ input limits ----------------------------- */

        @DefaultValue("60s") Duration maxDuration,
        @DefaultValue("500MB") DataSize maxUploadBytes,
        @DefaultValue("3840") int maxSourceWidth,
        @DefaultValue("2160") int maxSourceHeight,

        /* -------------------------------- ladder --------------------------------- */

        List<Rendition> ladder,
        @DefaultValue("30") int outputFps,
        @DefaultValue("2") int segmentSeconds,
        @DefaultValue("128k") String audioBitrate,
        @DefaultValue("48000") int audioSampleRate,
        @DefaultValue("2") int audioChannels,
        @DefaultValue("veryfast") String x264Preset,
        @DefaultValue("main") String x264Profile,

        /* ------------------------------- execution ------------------------------- */

        @DefaultValue("ffmpeg") String ffmpegPath,
        @DefaultValue("ffprobe") String ffprobePath,
        @DefaultValue("10m") Duration jobTimeout,
        @DefaultValue("/work") String workDir,

        /* -------------------------------- upload --------------------------------- */

        @DefaultValue("24") int uploadConcurrency,
        @DefaultValue("4m") Duration uploadTimeout,

        /* ------------------------------ thumbnails ------------------------------- */

        @DefaultValue("10") int spriteColumns,
        @DefaultValue("10") int spriteRows,
        @DefaultValue("240") int spriteTileWidth,
        @DefaultValue("3") int spriteJpegQuality,

        /* ------------------------------- reporting ------------------------------- */

        /** When true, raw ffmpeg stderr is embedded in the failure message. */
        @DefaultValue("false") boolean includeFfmpegStderrInFailure,

        /** Whether to verify the ffmpeg build's capabilities at startup and refuse to start if any are
         * deploy fails". */
        @DefaultValue("true") boolean verifyMediaToolchain
) {

    /** The configured ladder, or the built-in default when none is supplied. An explicit accessor
     * ladder instead of a null. */
    public List<Rendition> ladder() {
        return (ladder == null || ladder.isEmpty()) ? defaultLadder() : List.copyOf(ladder);
    }

    /** One rung. */
    public record Rendition(
            String name,
            int width,
            int height,
            int videoKbps,
            int maxrateKbps,
            int bufsizeKbps
    ) {

        public Rendition {
            if (width % 2 != 0 || height % 2 != 0) {
                throw new IllegalArgumentException(
                        "Rendition " + name + " must have even dimensions for H.264 4:2:0, got "
                                + width + "x" + height);
            }
            if (maxrateKbps < videoKbps) {
                throw new IllegalArgumentException(
                        "Rendition " + name + " has maxrateKbps < videoKbps");
            }
        }

        /** Convenience constructor for a rung with default VBV settings (~5% / 2x). */
        public static Rendition of(String name, int width, int height, int videoKbps) {
            int maxrate = Math.round(videoKbps * 1.05f);
            return new Rendition(name, width, height, videoKbps, maxrate, maxrate * 2);
        }
    }

    /** The default 9:16 ladder, highest first. Every rung is exact 9:16 with even dimensions, so the
     * transform never rounds and chroma never smears on the last row. */
    public static List<Rendition> defaultLadder() {
        return List.of(
                Rendition.of("v0", 1080, 1920, 4500),
                Rendition.of("v1", 720, 1280, 2200),
                Rendition.of("v2", 540, 960, 1100),
                Rendition.of("v3", 360, 640, 700),
                Rendition.of("v4", 270, 480, 350),
                Rendition.of("v5", 180, 320, 150)
        );
    }
}
