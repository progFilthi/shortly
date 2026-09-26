package com.shortly.transcoderservice.media;

import com.shortly.transcoderservice.config.TranscoderProperties;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegFormat;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import net.bramp.ffmpeg.shared.CodecType;
import org.apache.commons.lang3.math.Fraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads media metadata with ffprobe, the only authority in this pipeline. The declared
 * contentType is client-supplied and the key suffix is derived from it, so neither can be
 * trusted to describe the actual container.
 */
@Component
public class MediaProbeService {

    private static final Logger log = LoggerFactory.getLogger(MediaProbeService.class);

    private final FFprobe ffprobe;
    private final TranscoderProperties properties;

    public MediaProbeService(TranscoderProperties properties) {
        this.properties = properties;
        try {
            this.ffprobe = new FFprobe(properties.ffprobePath());
        } catch (IOException e) {
            // FfmpegCapabilities also verifies this on ApplicationReadyEvent, but failing here
            // means a misconfigured path is a startup error rather than a per-job error.
            throw new IllegalStateException(
                    "Could not initialise ffprobe at '" + properties.ffprobePath()
                            + "'. Is ffprobe installed and on PATH?", e);
        }
    }

    /**
     * Probes a local file.
     * <p>
     * Failures surface as {@link UnprobeableMediaException} rather than a raw bramp
     * exception: "we could not read this file" is a decision the pipeline has to make, and
     * it maps to a specific terminal failure reason.
     */
    public MediaMetadata probe(Path file) {
        FFmpegProbeResult result;
        try {
            result = ffprobe.probe(file.toAbsolutePath().toString());
        } catch (Exception e) {
            throw new UnprobeableMediaException(
                    "ffprobe could not read " + file.getFileName() + ": " + rootMessage(e), e);
        }

        if (result == null) {
            throw new UnprobeableMediaException("ffprobe returned no result for " + file.getFileName());
        }
        if (result.hasError()) {
            throw new UnprobeableMediaException("ffprobe reported an error: " + result.getError().string);
        }

        List<FFmpegStream> streams = result.getStreams();
        if (streams == null) {
            throw new UnprobeableMediaException("ffprobe reported no streams at all");
        }

        List<FFmpegStream> videoStreams = streams.stream()
                .filter(stream -> stream.codec_type == CodecType.VIDEO)
                .toList();
        if (videoStreams.isEmpty()) {
            throw new UnprobeableMediaException("File contains no video stream");
        }

        /*
         * Largest by pixel count. A file may carry a cover-art or thumbnail video stream
         * alongside the real one - some camera and social-export formats do exactly this -
         * and transcoding the wrong one wastes the entire job.
         */
        FFmpegStream video = videoStreams.stream()
                .max(Comparator.comparingLong(s -> (long) s.width * s.height))
                .orElseThrow(() -> new UnprobeableMediaException("No usable video stream"));

        Optional<FFmpegStream> audio = streams.stream()
                .filter(stream -> stream.codec_type == CodecType.AUDIO)
                .findFirst();

        int rotation = readRotation(video);
        boolean quarterTurn = rotation == 90 || rotation == 270;

        FFmpegFormat format = result.getFormat();

        return new MediaMetadata(
                toDuration(format, video, audio.orElse(null)),
                video.width,
                video.height,
                quarterTurn ? video.height : video.width,
                quarterTurn ? video.width : video.height,
                rotation,
                toDouble(video.avg_frame_rate),
                video.codec_name,
                video.pix_fmt,
                video.color_transfer,
                audio.isPresent(),
                audio.map(FFmpegStream::getCodecName).orElse(null),
                audio.map(FFmpegStream::getChannels).orElse(0),
                audio.map(FFmpegStream::getSampleRate).orElse(0),
                video.index,
                audio.map(FFmpegStream::getIndex).orElse(-1)
        );
    }

    /**
     * Rotation, from the stream's display-matrix side data - not any top-level ffprobe field.
     *
     * <p>ffmpeg applies the matrix during decode, so the encoder sees the rotated picture while
     * width/height validation sees the unrotated values: an iPhone portrait clip reports
     * 1920x1080 to ffprobe and 1080x1920 to the user.
     *
     * <p>Static so it can be tested directly - it cannot be tested by probing a real file,
     * because ffmpeg 8 cannot write a display matrix. See MediaProbeRotationTest.
     */
    static int readRotation(FFmpegStream stream) {
        FFmpegStream.SideData[] sideData = stream.side_data_list;
        if (sideData == null) {
            return 0;
        }
        for (FFmpegStream.SideData data : sideData) {
            if (data == null || data.side_data_type == null) {
                continue;
            }
            if (isDisplayMatrix(data.side_data_type)) {
                return normalizeDegrees(data.rotation);
            }
        }
        return 0;
    }

    /**
     * Matches ffprobe's {@code side_data_type} for a rotation matrix.
     * <p>
     * The literal value is {@code "Display Matrix"} — with a space. Comparing against a
     * lowercased {@code "displaymatrix"} silently never matches, which disables rotation
     * detection entirely and produces sideways output with no error anywhere. Non-alphanumeric
     * characters are stripped before comparing so the check is insensitive to how the value is
     * punctuated.
     */
    private static boolean isDisplayMatrix(String sideDataType) {
        return sideDataType.toLowerCase().replaceAll("[^a-z0-9]", "").contains("displaymatrix");
    }

    /** The display matrix is counter-clockwise; the convention used here is clockwise. */
    private static int normalizeDegrees(int rawRotation) {
        int degrees = ((rawRotation % 360) + 360) % 360;
        return degrees == 0 ? 0 : 360 - degrees;
    }

    /**
     * Container duration, falling back to the longest stream's for containers that only carry
     * per-stream timing. Zero is returned honestly rather than invented; the validator treats an
     * unreportable duration as a corrupt source.
     */
    private Duration toDuration(FFmpegFormat format, FFmpegStream video, FFmpegStream audio) {
        if (format != null && format.duration > 0) {
            return Duration.ofMillis(Math.round(format.duration * 1000));
        }
        double longest = Math.max(
                video == null ? 0d : video.duration,
                audio == null ? 0d : audio.duration);
        return longest > 0 ? Duration.ofMillis(Math.round(longest * 1000)) : Duration.ZERO;
    }

    private double toDouble(Fraction fraction) {
        if (fraction == null) {
            return 0d;
        }
        try {
            return fraction.doubleValue();
        } catch (ArithmeticException e) {
            // A 0/0 timebase is not fatal; it just means the frame rate is unknown, and the
            // command builder falls back to the configured output rate.
            log.debug("Unparseable frame rate fraction, treating as unknown", e);
            return 0d;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** Container-level tags, for callers that need the encoder or the major brand. */
    public static Optional<String> formatTag(FFmpegFormat format, String tag) {
        if (format == null) {
            return Optional.empty();
        }
        Map<String, String> tags = format.tags;
        return tags == null ? Optional.empty() : Optional.ofNullable(tags.get(tag));
    }
}
