package com.shortly.videoservice.enums;

/** Lifecycle of a video from the client's point of view. All five states are now reachable. {@code
 * {@code PROCESSING} once the upload is verified and handed to the transcoder. */
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
