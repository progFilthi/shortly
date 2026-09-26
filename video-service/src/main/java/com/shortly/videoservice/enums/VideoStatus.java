package com.shortly.videoservice.enums;

/**
 * Lifecycle of a video from the client's point of view.
 * <p>
 * All five states are now reachable. {@code PROCESSING} and {@code FAILED} previously existed
 * with no writer, so a client could never observe them even though the iOS model already
 * declared them.
 * <pre>
 *   PENDING ──▶ UPLOADING ──▶ PROCESSING ──▶ READY
 *                                  └──────▶ FAILED
 * </pre>
 * {@code UPLOADING} is set when a presigned URL is minted and {@code PROCESSING} once the
 * upload is verified and handed to the transcoder.
 */
public enum VideoStatus {
    PENDING,
    UPLOADING,
    PROCESSING,
    READY,
    FAILED;

    /** True once no further transition is expected without a new upload. */
    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }
}
