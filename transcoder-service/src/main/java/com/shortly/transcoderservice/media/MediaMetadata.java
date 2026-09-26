package com.shortly.transcoderservice.media;

import java.time.Duration;

/**
 * Everything the pipeline needs to know about an uploaded file, read from the bytes
 * themselves rather than from anything the client claimed.
 *
 * @param duration        container duration; for image sequences or broken metadata this may
 *                        be zero and {@link #durationReliable()} is then false
 * @param width           coded width BEFORE rotation metadata is applied. A portrait iPhone
 *                        clip is typically 1920x1080 with a 90 degree display matrix, so
 *                        this is frequently the transpose of what the user sees.
 * @param height          coded height before rotation, see {@link #width}
 * @param displayWidth    width after applying the rotation matrix - what the user sees
 * @param displayHeight   height after applying the rotation matrix
 * @param rotationDegrees clockwise display-matrix rotation, one of 0/90/180/270
 * @param frameRate       average frame rate of the primary video stream
 * @param videoCodec      source video codec, e.g. {@code h264}, {@code hevc}, {@code vp9}
 * @param pixelFormat     source pixel format, e.g. {@code yuv420p10le}
 * @param colorTransfer   colour transfer characteristic; {@code smpte2084} (PQ) and
 *                        {@code arib-std-b67} (HLG) mean the source is HDR and needs
 *                        tone mapping before it can be tagged as SDR
 * @param hasAudio        whether any audio stream is present. Drives whether the filtergraph
 *                        includes an {@code asplit} branch at all - see
 *                        {@link HlsCommandBuilder} for why this cannot be assumed true.
 * @param audioCodec      source audio codec, or null when {@link #hasAudio} is false
 * @param audioChannels   source channel count
 * @param audioSampleRate source sample rate in Hz
 * @param videoStreamIndex ffprobe index of the primary video stream
 * @param audioStreamIndex ffprobe index of the primary audio stream, or -1
 */
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

    /**
     * Duration is trustworthy when the container reported one and it is not obviously a
     * zero-duration placeholder. A zero duration must not be treated as "instant video".
     */
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
