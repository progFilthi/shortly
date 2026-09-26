package com.shortly.transcoderservice.media;

import com.shortly.contracts.media.TranscodeFailureReason;
import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.config.TranscoderProperties.Rendition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The admission gate. Runs after probing and before a single byte of encoding happens, so
 * an oversized or over-long upload costs one ffprobe invocation instead of a full ladder.
 */
@Component
public class MediaValidator {

    private static final Logger log = LoggerFactory.getLogger(MediaValidator.class);

    private final TranscoderProperties properties;

    public MediaValidator(TranscoderProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws MediaValidationException if the input can never become a playable shortform video
     */
    public void validate(MediaMetadata metadata, long fileSizeBytes) {
        if (!metadata.durationReliable()) {
            throw new MediaValidationException(TranscodeFailureReason.SOURCE_CORRUPT,
                    "Container reported no usable duration");
        }

        if (metadata.duration().compareTo(properties.maxDuration()) > 0) {
            throw new MediaValidationException(TranscodeFailureReason.DURATION_EXCEEDED,
                    "Video is " + metadata.duration().toSeconds() + "s, limit is "
                            + properties.maxDuration().toSeconds() + "s");
        }

        if (fileSizeBytes > properties.maxUploadBytes().toBytes()) {
            throw new MediaValidationException(TranscodeFailureReason.SIZE_EXCEEDED,
                    "Upload is " + fileSizeBytes + " bytes, limit is " + properties.maxUploadBytes().toBytes());
        }

        if (metadata.displayWidth() > properties.maxSourceWidth()
                || metadata.displayHeight() > properties.maxSourceHeight()) {
            throw new MediaValidationException(TranscodeFailureReason.RESOLUTION_EXCEEDED,
                    "Source is " + metadata.displayWidth() + "x" + metadata.displayHeight()
                            + ", limit is " + properties.maxSourceWidth() + "x"
                            + properties.maxSourceHeight());
        }

        if (metadata.displayWidth() <= 0 || metadata.displayHeight() <= 0) {
            throw new MediaValidationException(TranscodeFailureReason.SOURCE_CORRUPT,
                    "Source reported non-positive dimensions");
        }
    }

    /**
     * Drops rungs that would require upscaling.
     * <p>
     * Encoding a 1080p rung from a 720p source produces a larger, blurrier file that costs
     * bandwidth on every single view while looking worse than the 720p rung. Skipping it is
     * strictly better for the viewer and strictly cheaper for us.
     * <p>
     * The smallest rung is always kept: a 320p source still needs a rung it can fill, and a
     * ladder with no rung at or below the source size is unplayable.
     */
    public List<Rendition> applicableRungs(MediaMetadata metadata) {
        List<Rendition> configured = properties.ladder();
        List<Rendition> result = configured.stream()
                .filter(r -> r.width() <= metadata.displayWidth() && r.height() <= metadata.displayHeight())
                .toList();

        if (result.isEmpty()) {
            Rendition smallest = configured.get(configured.size() - 1);
            logUpscaleFallback(smallest, metadata);
            return List.of(smallest);
        }
        return result;
    }

    private void logUpscaleFallback(Rendition smallest, MediaMetadata metadata) {
        // Deliberately visible: producing an upscaled rung is a real cost and a real quality
        // compromise, and it should be something an operator can find in the logs.
        log.warn("Source {}x{} is smaller than the smallest configured rung {}x{}; that rung will be upscaled",
                metadata.displayWidth(), metadata.displayHeight(),
                smallest.width(), smallest.height());
    }
}
