package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.config.TranscoderProperties.Rendition;
import com.shortly.transcoderservice.media.MediaMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.shortly.transcoderservice.transcode.TranscodeFixtures.productionDefaults;
import static com.shortly.transcoderservice.transcode.TranscodeFixtures.threeRungs;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command string is the contract with ffmpeg, and a malformed one fails only at runtime, in
 * production, on the first real video. These assertions pin the three constraints that are easy
 * to get wrong and impossible to diagnose from a log: the audio branch, the per-variant stream
 * indices, and the aligned keyframes.
 */
class HlsCommandBuilderTest {

    private static final Path SOURCE = Path.of("/work/source.mp4");
    private static final Path OUTPUT = Path.of("/work/hls");

    private static MediaMetadata metadata(boolean hasAudio, boolean hdr) {
        return metadata(hasAudio, hdr, 30d);
    }

    private static MediaMetadata metadata(boolean hasAudio, boolean hdr, double frameRate) {
        return new MediaMetadata(
                Duration.ofSeconds(30), 1080, 1920, 1080, 1920, 0, frameRate,
                "h264", hdr ? "yuv420p10le" : "yuv420p",
                hdr ? "smpte2084" : "bt709",
                hasAudio, hasAudio ? "aac" : null,
                hasAudio ? 2 : 0, hasAudio ? 48000 : 0, 0, hasAudio ? 1 : -1);
    }

    private static List<String> build(TranscoderProperties properties,
                                      MediaMetadata media,
                                      List<Rendition> rungs) {
        return new HlsCommandBuilder(properties).build(SOURCE, media, rungs, OUTPUT);
    }

    private static String optionOf(List<String> command, String option) {
        return command.get(command.indexOf(option) + 1);
    }

    private static String graphOf(List<String> command) {
        return optionOf(command, "-filter_complex");
    }

    @Test
    void emitsOneSplitPerRungAndOneMappedStreamPerRungPerDimension() {
        List<String> command = build(productionDefaults(), metadata(true, false), threeRungs());

        String graph = graphOf(command);
        // Six mapped streams for three rungs: three video, three audio.
        assertThat(command.stream().filter("-map"::equals).count()).isEqualTo(6);
        assertThat(graph).contains("split=3")
                .contains("[s0]").contains("[s1]").contains("[s2]")
                .contains("[v0]").contains("[v1]").contains("[v2]")
                .contains("[a0]").contains("[a1]").contains("[a2]");
    }

    @Test
    void givesEveryVariantItsOwnAudioStreamIndex() {
        // Reusing one index across variants makes ffmpeg abort with "Same elementary stream
        // found more than once in two different variant definitions". This is the single most
        // easily reintroduced bug in this file, so it is asserted explicitly.
        List<String> command = build(productionDefaults(), metadata(true, false), threeRungs());

        assertThat(optionOf(command, "-var_stream_map")).isEqualTo("v:0,a:0 v:1,a:1 v:2,a:2");
    }

    @Test
    void synthesisesSilenceRatherThanReferencingAMissingAudioStream() {
        // A video with no audio stream is normal, not an edge case. Referencing [0:a] when there
        // is none aborts the whole filtergraph with "matches no streams", so the audio branch
        // must be built conditionally on the probe result.
        List<String> command = build(productionDefaults(), metadata(false, false), threeRungs());

        String graph = graphOf(command);
        assertThat(command).contains("anullsrc=channel_layout=stereo:sample_rate=48000");
        assertThat(graph).contains("[1:a]aresample=48000,asplit=3");
        assertThat(graph).doesNotContain("[0:a]");
    }

    @Test
    void forcesAlignedConstantGopKeyframesForEveryRung() {
        List<String> command = build(productionDefaults(), metadata(true, false), threeRungs());

        // 2s segments at 30fps is a 60-frame GOP on every rendition, with scene-cut keyframes
        // disabled, so segment boundaries line up across renditions and ABR switching is clean.
        assertThat(optionOf(command, "-g")).isEqualTo("60");
        assertThat(optionOf(command, "-keyint_min")).isEqualTo("60");
        assertThat(optionOf(command, "-sc_threshold")).isEqualTo("0");
        assertThat(optionOf(command, "-force_key_frames")).isEqualTo("expr:gte(t,n_forced*2)");
    }

    @Test
    void toneMapsHdrSourcesAndLeavesSdrAlone() {
        HlsCommandBuilder builder = new HlsCommandBuilder(productionDefaults());

        String hdrGraph = graphOf(builder.build(SOURCE, metadata(true, true), threeRungs(), OUTPUT));
        String sdrGraph = graphOf(builder.build(SOURCE, metadata(true, false), threeRungs(), OUTPUT));

        // Without this a recent iPhone HDR clip reaches the feed washed out and flat.
        assertThat(hdrGraph).contains("zscale=t=linear").contains("tonemap=tonemap=hable");
        assertThat(sdrGraph).doesNotContain("tonemap");
    }

    @Test
    void cropsToTheCanonicalAspectRatherThanUpscalingOrPadding() {
        String graph = graphOf(build(productionDefaults(), metadata(true, false), threeRungs()));

        // 'increase' then crop equals cover: fill the 9:16 frame and lose the sides.
        assertThat(graph)
                .contains("scale=1080:1920:force_original_aspect_ratio=increase")
                .contains("crop=1080:1920")
                .contains("setsar=1");
    }

    @Test
    void neverUpsamplesAFrameRate() {
        String graph = graphOf(build(productionDefaults(), metadata(true, false, 24d), threeRungs()));

        // A 24fps source stays 24fps. Duplicating frames up to 30 would inflate bitrate for no
        // visible gain.
        assertThat(graph).contains("fps=24,").doesNotContain("fps=30,");
    }

    @Test
    void downsamplesHighFrameRateSourcesToTheOutputRate() {
        String graph = graphOf(build(productionDefaults(), metadata(true, false, 60d), threeRungs()));

        assertThat(graph).contains("fps=30,");
    }

    @Test
    void configuresVodPlaylistWithIndependentSegments() {
        List<String> command = build(productionDefaults(), metadata(true, false), threeRungs());

        assertThat(optionOf(command, "-hls_playlist_type")).isEqualTo("vod");
        assertThat(optionOf(command, "-hls_list_size")).isEqualTo("0");
        assertThat(optionOf(command, "-hls_flags")).isEqualTo("independent_segments+temp_file");
        assertThat(optionOf(command, "-master_pl_name")).isEqualTo("master.m3u8");
    }

    @Test
    void stopsAtTheShortestStreamSoThereIsNoPaddedTail() {
        // -short is not a valid ffmpeg option; -shortest is. Getting this wrong fails every job,
        // and the integration test is what catches it.
        List<String> command = build(productionDefaults(), metadata(true, false), threeRungs());

        assertThat(command).contains("-shortest").doesNotContain("-short");
    }

    @Test
    void emitsStereoAudioRegardlessOfSourceChannelCount() {
        List<String> command = build(productionDefaults(), metadata(true, false), threeRungs());

        assertThat(optionOf(command, "-ac")).isEqualTo("2");
        assertThat(optionOf(command, "-c:a")).isEqualTo("aac");
    }
}
