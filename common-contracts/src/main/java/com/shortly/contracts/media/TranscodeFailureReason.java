package com.shortly.contracts.media;

/**
 * Machine-readable reason a video failed to reach {@code READY}.
 * <p>
 * Clients should switch on this rather than parse human-readable messages.
 * {@link #isRetryable()} tells the platform whether re-queueing the job could plausibly
 * succeed, as opposed to a permanently bad input.
 */
public enum TranscodeFailureReason {

    /** The source object referenced by the event does not exist in the bucket. */
    SOURCE_MISSING(false),

    /** Source exists but could not be demuxed or decoded at all. */
    SOURCE_CORRUPT(false),

    /** Source has no decodable video stream. */
    NO_VIDEO_STREAM(false),

    /** Source exceeds the configured maximum duration. */
    DURATION_EXCEEDED(false),

    /** Source exceeds the configured maximum byte size. */
    SIZE_EXCEEDED(false),

    /** Source exceeds the configured maximum pixel count. */
    RESOLUTION_EXCEEDED(false),

    /** ffmpeg exited non-zero or produced no output. */
    TRANSCODE_FAILED(true),

    /** ffmpeg exceeded the wall-clock budget for a single job. */
    TIMEOUT(true),

    /** Uploading generated artifacts to object storage failed. */
    UPLOAD_FAILED(true),

    /** No rendition satisfied the constraints (e.g. source smaller than the smallest rung). */
    LADDER_UNSATISFIABLE(false);

    private final boolean retryable;

    TranscodeFailureReason(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
