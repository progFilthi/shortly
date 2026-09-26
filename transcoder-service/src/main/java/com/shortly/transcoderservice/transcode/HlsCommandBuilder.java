package com.shortly.transcoderservice.transcode;

import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.config.TranscoderProperties.Rendition;
import com.shortly.transcoderservice.media.MediaMetadata;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Builds the single ffmpeg invocation that produces the whole ladder.
 *
 * <p>One process, not one per rung: the source is decoded once and the frames fanned out to
 * every encoder. Six separate encodes decode it six times, which is the largest single cost
 * lever here.
 *
 * <p>Two ffmpeg constraints shape the audio handling, both verified against 8.1.2:
 * <ul>
 *   <li>Reusing an audio index across variants ({@code v:0,a:0 v:1,a:0}) aborts with "Same
 *       elementary stream found more than once". Each variant needs its own index, hence
 *       {@code asplit}.</li>
 *   <li>With no audio stream at all, {@code [0:a]asplit} matches nothing and takes the whole
 *       filtergraph down. So the graph is built conditionally - a silent clip is normal.</li>
 * </ul>
 *
 * <p>Each variant carries its own audio, so the master needs no EXT-X-MEDIA group. Slightly
 * more bytes, substantially better player compatibility.
 */
@Component
public class HlsCommandBuilder {

    private final TranscoderProperties properties;

    public HlsCommandBuilder(TranscoderProperties properties) {
        this.properties = properties;
    }

    /**
     * @param rungs ladder to produce, highest first, already filtered to exclude upscaling
     */
    public List<String> build(Path source,
                              MediaMetadata metadata,
                              List<Rendition> rungs,
                              Path outputDir) {

        if (rungs.isEmpty()) {
            throw new IllegalArgumentException("Cannot build a ladder with no rungs");
        }

        List<String> command = new ArrayList<>();
        command.add(properties.ffmpegPath());

        // -y overwrites a previous attempt's files. Safe because each job gets a unique
        // working directory, and it avoids a stale-segment failure mode.
        command.add("-y");
        command.add("-nostdin");
        command.add("-hide_banner");
        // "error" normally, so a successful job's log stays readable. Raised to "warning" only
        // for HDR, where tone mapping emits diagnostics worth having.
        command.add("-loglevel");
        command.add(metadata.isHdr() ? "warning" : "error");

        command.add("-i");
        command.add(source.toAbsolutePath().toString());

        if (!metadata.hasAudio()) {
            // A synthetic silent track, so every variant is audio+video and no player has to
            // special-case a video-only rendition set.
            command.add("-f");
            command.add("lavfi");
            command.add("-i");
            command.add("anullsrc=channel_layout=stereo:sample_rate=" + properties.audioSampleRate());
        }

        command.add("-filter_complex");
        command.add(buildFilterGraph(metadata, rungs));

        addStreamMaps(command, metadata, rungs);
        addVideoEncoderOptions(command, rungs);
        addAudioEncoderOptions(command);

        addHlsMuxerOptions(command, outputDir, rungs);

        return List.copyOf(command);
    }

    /**
     * One split per dimension, a scale+crop per rung, and an asplit when there is audio.
     *
     * <p>The transform is always "scale to cover, then centre-crop to 9:16". Cropping to fill is
     * a product decision - it matches user expectation for shortform and costs nothing - but
     * landscape and square uploads do lose their edges.
     */
    private String buildFilterGraph(MediaMetadata metadata, List<Rendition> rungs) {
        StringJoiner splitOutputs = new StringJoiner("");
        for (int i = 0; i < rungs.size(); i++) {
            splitOutputs.add("[s" + i + "]");
        }

        StringBuilder graph = new StringBuilder();
        graph.append("[0:v]split=").append(rungs.size()).append(splitOutputs);

        for (int i = 0; i < rungs.size(); i++) {
            Rendition rung = rungs.get(i);
            graph.append(";[s").append(i).append(']')
                    // Frame rate first, so the scale filters only see output-rate frames: a
                    // 60fps source then costs the same to scale as a 30fps one.
                    .append(formatRate(metadata))
                    .append(tonemapChain(metadata))
                    .append("scale=").append(rung.width()).append(':').append(rung.height())
                    // 'increase' covers the target box, so the crop always has material to cut.
                    .append(":force_original_aspect_ratio=increase")
                    .append(",crop=").append(rung.width()).append(':').append(rung.height())
                    // Square pixels, or a non-1:1 source aspect ratio renders stretched.
                    .append(",setsar=1")
                    .append("[v").append(i).append(']');
        }

        if (metadata.hasAudio()) {
            StringJoiner audioOutputs = new StringJoiner("");
            for (int i = 0; i < rungs.size(); i++) {
                audioOutputs.add("[a" + i + "]");
            }
            graph.append(";[0:a]aresample=").append(properties.audioSampleRate())
                    .append(",asplit=").append(rungs.size())
                    .append(audioOutputs);
        } else {
            // Input 1 is the synthetic silent track added in build().
            StringJoiner audioOutputs = new StringJoiner("");
            for (int i = 0; i < rungs.size(); i++) {
                audioOutputs.add("[a" + i + "]");
            }
            graph.append(";[1:a]aresample=").append(properties.audioSampleRate())
                    .append(",asplit=").append(rungs.size())
                    .append(audioOutputs);
        }

        return graph.toString();
    }

    /**
     * Forces a constant output frame rate and caps the source rate.
     * <p>
     * Variable frame rate is normal for phone-recorded video. Left alone it produces uneven
     * GOP lengths, which breaks the aligned-keyframes property that adaptive switching
     * depends on, and it makes the segment count unpredictable.
     */
    private String formatRate(MediaMetadata metadata) {
        double target = properties.outputFps();
        if (metadata.frameRate() > 0 && metadata.frameRate() < target) {
            // Never manufacture frames. A 24fps source stays 24fps rather than being
            // duplicated up to 30, which would inflate bitrate for no visible gain.
            return "fps=" + trim(metadata.frameRate()) + ",";
        }
        return "fps=" + trim(target) + ",";
    }

    /**
     * HDR sources need an explicit tone map, otherwise a recent iPhone clip lands in the
     * feed washed out and flat (PQ) or with blown highlights (HLG). Players assume SDR.
     * <p>
     * The chain is: linearise with the source transfer, apply a perceptual tone curve,
     * then re-encode to BT.709 with a limited range. {@code zscale} does the colour
     * science and {@code tonemap} the curve; both are present in the pinned Alpine ffmpeg
     * build, and {@link #verifyFilterAvailability} checks at startup rather than letting the
     * first HDR upload fail in production.
     * <p>
     * Applied identically to every rung, before the scale, so all renditions are graded the
     * same and do not drift in colour when a player switches between them.
     */
    private String tonemapChain(MediaMetadata metadata) {
        if (!metadata.isHdr()) {
            return "";
        }
        return "zscale=t=linear:npl=100,"
                + "format=gbrpf32le,"
                + "zscale=p=bt709,"
                + "tonemap=tonemap=hable:desat=0:peak=100,"
                + "zscale=t=bt709:m=bt709:r=tv,"
                + "format=yuv420p,";
    }

    /**
     * Interleaves each video stream with its own audio stream, in rung order. The order here
     * is what the {@code v:N} / {@code a:N} indices in {@code var_stream_map} refer to.
     */
    private void addStreamMaps(List<String> command, MediaMetadata metadata, List<Rendition> rungs) {
        for (int i = 0; i < rungs.size(); i++) {
            command.add("-map");
            command.add("[v" + i + "]");
            command.add("-map");
            command.add("[a" + i + "]");
        }
        // -shortest stops encoding when the shortest mapped stream ends. Without it a source
        // whose audio is slightly shorter than its video gets a silent padded tail, and one
        // whose audio is longer gets a frozen final frame.
        command.add("-shortest");
    }

    /**
     * Per-rung rate control, addressed by output stream index rather than by name.
     * <p>
     * The index numbering is global across all mapped video streams, which is why these are
     * {@code -b:v:0..N} and not per-output options. Getting this wrong is silent: ffmpeg
     * applies the encoder's own default bitrate to the un-addressed streams and the ladder
     * comes out with several identical rungs.
     */
    private void addVideoEncoderOptions(List<String> command, List<Rendition> rungs) {
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(properties.x264Preset());
        command.add("-profile:v");
        command.add(properties.x264Profile());
        command.add("-pix_fmt");
        command.add("yuv420p");

        // Constant GOP, no scene-cut keyframes. A keyframe in one rendition but not its
        // neighbours is what makes a player stall mid-switch.
        command.add("-sc_threshold");
        command.add("0");

        int gopSeconds = properties.segmentSeconds();
        int gop = gopSeconds * properties.outputFps();
        command.add("-g");
        command.add(String.valueOf(gop));
        command.add("-keyint_min");
        command.add(String.valueOf(gop));

        // Absolute-time keyframes, identical across renditions, so a player can switch at a
        // segment boundary without waiting for a new keyframe.
        command.add("-force_key_frames");
        command.add("expr:gte(t,n_forced*" + gopSeconds + ")");

        for (int i = 0; i < rungs.size(); i++) {
            Rendition rung = rungs.get(i);
            command.add("-b:v:" + i);
            command.add(rung.videoKbps() + "k");
            command.add("-maxrate:v:" + i);
            command.add(rung.maxrateKbps() + "k");
            command.add("-bufsize:v:" + i);
            command.add(rung.bufsizeKbps() + "k");
        }
    }

    private void addAudioEncoderOptions(List<String> command) {
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add(properties.audioBitrate());
        command.add("-ar");
        command.add(String.valueOf(properties.audioSampleRate()));
        // Stereo, always. A mono source should not produce a track that is silent on one
        // side, and a 5.1 source should not force a 5.1 ladder.
        command.add("-ac");
        command.add(String.valueOf(properties.audioChannels()));
    }

    private void addHlsMuxerOptions(List<String> command, Path outputDir, List<Rendition> rungs) {
        command.add("-f");
        command.add("hls");

        // VOD, not EVENT. Content here is immutable once produced, and EVENT also re-signs a
        // growing playlist, which is a live-streaming behaviour.
        command.add("-hls_playlist_type");
        command.add("vod");

        // Keep every segment. Required for VOD; otherwise the playlist is a sliding window
        // that cannot be seeked.
        command.add("-hls_list_size");
        command.add("0");

        // Every segment starts with a keyframe thanks to -force_key_frames, so this tells a
        // player it can switch without re-decoding.
        command.add("-hls_flags");
        command.add("independent_segments+temp_file");

        // MPEG-TS over fMP4: broader device support for the same effort, and nothing here
        // needs CMAF.
        command.add("-hls_segment_type");
        command.add("mpegts");

        // %v expands to the variant index, mirroring -var_stream_map.
        command.add("-hls_segment_filename");
        command.add(outputDir.resolve("v%v").resolve("seg_%05d.ts").toAbsolutePath().toString());

        command.add("-master_pl_name");
        command.add("master.m3u8");

        // The joiner's delimiter and nothing else; an extra one yields "v:0,a:0   v:1,a:1".
        StringJoiner map = new StringJoiner(" ");
        for (int i = 0; i < rungs.size(); i++) {
            map.add("v:" + i + ",a:" + i);
        }
        command.add("-var_stream_map");
        command.add(map.toString());

        command.add(outputDir.resolve("v%v").resolve("index.m3u8").toAbsolutePath().toString());
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(Math.round(value * 100) / 100.0);
    }
}
