package com.shortly.transcoderservice.media;

import java.time.Duration;

/** Everything the pipeline needs to know about an uploaded file, read from the bytes themselves
 * rather than from anything the client claimed. */
public record MediaMetadata(
        Duration duration,
        int width,
        int height,
        int displayWidth,
        int displayHeight,
        int rotationDegrees,
        double frameRate,
        String videoCodec,
        String pixelFormat,
        String colorTransfer,
        boolean hasAudio,
        String audioCodec,
        int audioChannels,
        int audioSampleRate,
        int videoStreamIndex,
        int audioStreamIndex
) {

    public boolean isPortrait() {
        return displayHeight > displayWidth;
    }

    /** True when the source is already effectively 9:16 and needs no reframing. */
    public boolean isCanonicalAspect() {
        if (displayHeight <= 0 || displayWidth <= 0) {
            return false;
        }
        double ratio = (double) displayWidth / displayHeight;
        return Math.abs(ratio - 9.0 / 16.0) < 0.01;
    }

    public long pixelCount() {
        return (long) displayWidth * displayHeight;
    }

    /** Duration is trustworthy when the container reported one and it is not obviously a zero-duration
     * placeholder. */
    public boolean durationReliable() {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    public boolean isHdr() {
        return "smpte2084".equalsIgnoreCase(colorTransfer)
                || "arib-std-b67".equalsIgnoreCase(colorTransfer);
    }

    public boolean isHighBitDepth() {
        return pixelFormat != null && (pixelFormat.contains("10le") || pixelFormat.contains("12le"));
    }
}
