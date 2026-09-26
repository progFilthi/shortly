package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.config.TranscoderProperties.Rendition;
import com.shortly.transcoderservice.media.MediaMetadata;
import com.shortly.transcoderservice.media.MediaProbeService;
import com.shortly.transcoderservice.thumbnail.ThumbnailExtractor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.shortly.transcoderservice.transcode.TranscodeFixtures.withWorkDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real command the pipeline would build, against the real ffmpeg, and inspects the
 * real output tree.
 * <p>
 * Unit tests on the command string cannot catch a filter that ffmpeg rejects, a variant map
 * it mis-parses, or a muxer option that changed meaning. This does, and it is the only test
 * that would have caught the "Same elementary stream found more than once" failure.
 * <p>
 * Skipped automatically when ffmpeg or ffprobe is not on PATH, so it does not break a build
 * on a machine without a media toolchain.
 */
class HlsCommandBuilderIT {

    @BeforeAll
    static void requireMediaToolchain() {
        // Skipped, not failed, when ffmpeg/ffprobe is absent: these tests exercise a binary
        // contract, so there is nothing meaningful to assert without the binary.
        Assumptions.assumeTrue(onPath("ffmpeg"), "ffmpeg not on PATH; skipping integration test");
        Assumptions.assumeTrue(onPath("ffprobe"), "ffprobe not on PATH; skipping integration test");
    }

    private static boolean onPath(String binary) {
        try {
            return new ProcessBuilder(binary, "-version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Builds a synthetic clip so the test has no fixture files in the repo. */
    private Path syntheticSource(Path dir, String name, int width, int height, boolean withAudio)
            throws Exception {
        Path source = dir.resolve(name);
        List<String> command = new java.util.ArrayList<>(List.of(
                "ffmpeg", "-y", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i",
                "testsrc2=size=" + width + "x" + height + ":rate=30:duration=6"));
        if (withAudio) {
            command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=6"));
        }
        command.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv420p", source.toAbsolutePath().toString()));
        new ProcessBuilder(command).redirectErrorStream(true).start().waitFor();
        return source;
    }

    @Test
    void producesPlayableLadderForVideoWithAudio(@TempDir Path dir) throws Exception {
        Path source = syntheticSource(dir, "with-audio.mp4", 1080, 1920, true);
        assertThat(Files.exists(source)).isTrue();

        TranscoderProperties props = withWorkDir(dir.toString());
        MediaMetadata metadata = new MediaProbeService(props).probe(source);
        assertThat(metadata.hasAudio()).isTrue();
        assertThat(metadata.duration().toSeconds()).isBetween(5L, 7L);

        List<Rendition> rungs = List.of(
                Rendition.of("v0", 1080, 1920, 4500),
                Rendition.of("v1", 540, 960, 1100),
                Rendition.of("v2", 180, 320, 150));

        Path hlsDir = dir.resolve("hls");
        Files.createDirectories(hlsDir);

        List<String> command = new HlsCommandBuilder(props)
                .build(source, metadata, rungs, hlsDir);

        FfmpegExecutor.Result result = new FfmpegExecutor(props).run(command, props.jobTimeout());
        assertThat(result.stderr())
                .as("ffmpeg diagnostics for the command the pipeline actually builds")
                .isEmpty();
        assertThat(result.exitCode())
                .as("ffmpeg must succeed for the command the pipeline actually builds")
                .isZero();

        Path master = hlsDir.resolve("master.m3u8");
        assertThat(Files.exists(master)).isTrue();

        String manifest = Files.readString(master);
        assertThat(manifest).startsWith("#EXTM3U");
        // One line per rendition, highest first, with the resolution ffmpeg actually produced.
        assertThat(manifest).contains("RESOLUTION=1080x1920");
        assertThat(manifest).contains("RESOLUTION=540x960");
        assertThat(manifest).contains("RESOLUTION=180x320");
        assertThat(manifest).contains("CODECS=");
        assertThat(manifest).contains("v0/index.m3u8", "v1/index.m3u8", "v2/index.m3u8");

        for (Rendition rung : rungs) {
            Path mediaPlaylist = hlsDir.resolve(rung.name()).resolve("index.m3u8");
            assertThat(Files.exists(mediaPlaylist))
                    .as("media playlist for %s", rung.name()).isTrue();

            String playlist = Files.readString(mediaPlaylist);
            assertThat(playlist).contains("#EXT-X-PLAYLIST-TYPE:VOD");
            assertThat(playlist).contains("#EXT-X-INDEPENDENT-SEGMENTS");
            assertThat(playlist).doesNotContain(".tmp");

            long segments = Files.list(hlsDir.resolve(rung.name()))
                    .filter(p -> p.toString().endsWith(".ts"))
                    .count();
            assertThat(segments)
                    .as("6s clip at 2s segments should yield 3 segments for %s", rung.name())
                    .isBetween(2L, 4L);
        }
    }

    @Test
    void producesPlayableLadderForSilentVideo(@TempDir Path dir) throws Exception {
        // The regression guard for the case that aborts the whole filtergraph if the audio
        // branch is built unconditionally.
        Path source = syntheticSource(dir, "silent.mp4", 1080, 1920, false);
        assertThat(Files.exists(source)).isTrue();

        TranscoderProperties props = withWorkDir(dir.toString());
        MediaMetadata metadata = new MediaProbeService(props).probe(source);
        assertThat(metadata.hasAudio()).isFalse();

        List<Rendition> rungs = List.of(
                Rendition.of("v0", 540, 960, 1100),
                Rendition.of("v1", 270, 480, 350));

        Path hlsDir = dir.resolve("hls");
        Files.createDirectories(hlsDir);

        FfmpegExecutor.Result result = new FfmpegExecutor(props).run(
                new HlsCommandBuilder(props).build(source, metadata, rungs, hlsDir),
                props.jobTimeout());

        assertThat(result.stderr()).isEmpty();
        assertThat(result.exitCode())
                .as("a video with no audio stream must still produce a full ladder")
                .isZero();
        assertThat(Files.readString(hlsDir.resolve("master.m3u8")))
                .contains("RESOLUTION=540x960")
                .contains("RESOLUTION=270x480");
    }

    @Test
    void centreCropsLandscapeSourceToTheCanonicalAspect(@TempDir Path dir) throws Exception {
        // 1920x1080 landscape in, 9:16 out - the sides are cropped, not letterboxed.
        Path source = syntheticSource(dir, "landscape.mp4", 1920, 1080, true);

        TranscoderProperties props = withWorkDir(dir.toString());
        MediaMetadata metadata = new MediaProbeService(props).probe(source);
        assertThat(metadata.isCanonicalAspect()).isFalse();

        List<Rendition> rungs = List.of(Rendition.of("v0", 1080, 1920, 4500));
        Path hlsDir = dir.resolve("hls");
        Files.createDirectories(hlsDir);

        FfmpegExecutor.Result result = new FfmpegExecutor(props).run(
                new HlsCommandBuilder(props).build(source, metadata, rungs, hlsDir),
                props.jobTimeout());
        assertThat(result.stderr()).isEmpty();
        assertThat(result.exitCode()).isZero();

        assertThat(Files.readString(hlsDir.resolve("master.m3u8")))
                .as("a landscape source must be emitted as 1080x1920")
                .contains("RESOLUTION=1080x1920");
    }

    @Test
    void extractsPosterAndScrubSprite(@TempDir Path dir) throws Exception {
        Path source = syntheticSource(dir, "thumbs.mp4", 1080, 1920, true);
        TranscoderProperties props = withWorkDir(dir.toString());
        MediaMetadata metadata = new MediaProbeService(props).probe(source);

        Path thumbDir = dir.resolve("thumbs");
        Files.createDirectories(thumbDir);

        List<Double> intervals = new ThumbnailExtractor(new FfmpegExecutor(props), props)
                .extract(source, metadata, thumbDir);

        assertThat(Files.exists(thumbDir.resolve("poster.jpg"))).isTrue();
        assertThat(Files.size(thumbDir.resolve("poster.jpg"))).isPositive();
        assertThat(Files.exists(thumbDir.resolve("sprite.jpg"))).isTrue();
        assertThat(intervals).isNotEmpty();
        // Sampling must stay inside the clip and skip the very first frames.
        assertThat(intervals.get(0)).isGreaterThanOrEqualTo(0d);
        assertThat(intervals.get(intervals.size() - 1))
                .isLessThanOrEqualTo(metadata.duration().toSeconds() + 0.5);
    }
}
