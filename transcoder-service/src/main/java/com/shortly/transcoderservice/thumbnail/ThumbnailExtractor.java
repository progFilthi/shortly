package com.shortly.transcoderservice.thumbnail;

import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.media.MediaMetadata;
import com.shortly.transcoderservice.transcode.FfmpegExecutionException;
import com.shortly.transcoderservice.transcode.FfmpegExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the poster frame and the scrub sprite sheet.
 *
 * <p>This service is the only one with ffmpeg, so it is the only place cover art can be made -
 * and doing it here reuses the already-downloaded source, so it costs no extra transfer.
 *
 * <p>Sampling is uniform but skips the first half second, which for a phone recording is
 * usually a black or half-opened-camera frame and makes a terrible default cover.
 */
@Component
public class ThumbnailExtractor {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailExtractor.class);

    /**
     * A still at t=0 is very often a black frame or a partially-opened camera. Skipping the
     * opening of every clip costs nothing and makes the default cover dramatically better.
     */
    private static final double FIRST_SAMPLE_SECONDS = 0.5;

    /** Below this the frame rate of the output image is meaningless. */
    private static final int MIN_TILE_HEIGHT = 120;

    private final FfmpegExecutor executor;
    private final TranscoderProperties properties;

    public ThumbnailExtractor(FfmpegExecutor executor, TranscoderProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * Produces {@code poster.jpg} and {@code sprite.jpg} in the given directory.
     *
     * @return the sampling grid used, or empty when the clip is too short to sample
     */
    public List<Double> extract(Path source, MediaMetadata metadata, Path outputDir) {
        double durationSeconds = metadata.duration().toMillis() / 1000.0;
        if (durationSeconds <= FIRST_SAMPLE_SECONDS) {
            log.info("Source is too short ({}s) to sample a sprite sheet; poster only", durationSeconds);
            extractPoster(source, outputDir.resolve("poster.jpg"), FIRST_SAMPLE_SECONDS);
            return List.of();
        }

        int requested = properties.spriteColumns() * properties.spriteRows();
        int frameCount = Math.max(1, Math.min(requested, (int) Math.floor(durationSeconds * 2)));

        // Uniform grid, offset past the opening frames.
        double first = Math.min(FIRST_SAMPLE_SECONDS, durationSeconds / 2);
        double step = (durationSeconds - first) / frameCount;

        List<Double> intervals = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            intervals.add(round(first + (i * step)));
        }

        extractPoster(source, outputDir.resolve("poster.jpg"), intervals.get(0));
        extractSprite(source, outputDir.resolve("sprite.jpg"), intervals);

        return List.copyOf(intervals);
    }

    /** One frame at the canonical output width, so the cover matches the first frame a viewer sees. */
    private void extractPoster(Path source, Path destination, double timestampSeconds) {
        int width = properties.ladder().get(0).width();
        int height = tileHeightFor(properties.ladder().get(0).height());

        List<String> command = new ArrayList<>(List.of(
                properties.ffmpegPath(),
                "-y", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-ss", format(timestampSeconds),
                "-i", source.toAbsolutePath().toString(),
                "-frames:v", "1",
                // Same tone map as the ladder, so the cover is not a different grade
                // from the video it represents.
                "-vf", "scale=" + width + ":" + height + ":force_original_aspect_ratio=increase"
                        + ",crop=" + width + ":" + height + ",setsar=1",
                "-q:v", "2",
                destination.toAbsolutePath().toString()
        ));

        requireSuccess(executor.run(command, properties.jobTimeout()),
                "poster extraction", destination);
    }

    /**
     * One image containing every candidate frame in a grid, from a single ffmpeg pass: a
     * {@code select} over an explicit frame list plus the {@code tile} layout filter. Sampling
     * frames individually and compositing separately would re-decode the source N times.
     */
    private void extractSprite(Path source, Path destination, List<Double> intervals) {
        int tileWidth = properties.spriteTileWidth();
        int tileHeight = tileHeightFor(tileWidth);
        int columns = properties.spriteColumns();
        int rows = (int) Math.ceil((double) intervals.size() / columns);

        StringBuilder select = new StringBuilder("select='");
        for (int i = 0; i < intervals.size(); i++) {
            if (i > 0) {
                select.append('+');
            }
            // between(t, t, t+epsilon) keeps the frame nearest the sampled instant without
            // requiring an exact PTS match, which real captures never have.
            select.append("between(t,").append(format(intervals.get(i)))
                    .append(',').append(format(intervals.get(i) + 0.05)).append(')');
        }
        select.append('\'');

        String filter = select + ",scale=" + tileWidth + ":" + tileHeight
                + ":force_original_aspect_ratio=increase"
                + ",crop=" + tileWidth + ":" + tileHeight
                + ",setsar=1"
                + ",tile=" + columns + "x" + rows;

        List<String> command = new ArrayList<>(List.of(
                properties.ffmpegPath(),
                "-y", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-i", source.toAbsolutePath().toString(),
                "-vf", filter,
                "-frames:v", "1",
                "-q:v", String.valueOf(properties.spriteJpegQuality()),
                destination.toAbsolutePath().toString()
        ));

        requireSuccess(executor.run(command, properties.jobTimeout()),
                "sprite extraction", destination);
    }

    /**
     * Derives tile height from tile width using the canonical 9:16 ratio, forced even.
     * Fixing the aspect here rather than per-tile keeps the whole grid uniform, which is what
     * lets the client index tiles with plain arithmetic.
     */
    private int tileHeightFor(int width) {
        int height = Math.round(width * 16f / 9f);
        return Math.max(MIN_TILE_HEIGHT, height % 2 == 0 ? height : height + 1);
    }

    private void requireSuccess(FfmpegExecutor.Result result, String what, Path output) {
        if (!result.succeeded()) {
            log.error("{} failed with exit code {}: {}", what, result.exitCode(), result.stderr());
            throw new FfmpegExecutionException(
                    what + " failed with exit code " + result.exitCode()
                            + " (expected output " + output + "): " + firstLine(result.stderr()));
        }
        if (!Files.exists(output) || fileSize(output) == 0) {
            throw new FfmpegExecutionException(what + " produced no output at " + output);
        }
    }

    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "no diagnostic output";
        }
        int newline = text.indexOf('\n');
        return newline > 0 ? text.substring(0, newline) : text;
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Locale-independent, and stable to 3 decimals, which is well below a frame at 30fps. */
    private String format(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", Math.max(0d, seconds));
    }

    private double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
