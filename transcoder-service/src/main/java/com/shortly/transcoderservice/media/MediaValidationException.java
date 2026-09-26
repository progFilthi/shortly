package com.shortly.transcoderservice.media;

import com.shortly.contracts.media.TranscodeFailureReason;

/**
 * The input violates a platform limit. Carries a machine-readable reason so the failure
 * event tells the client which limit it hit, rather than just "invalid video".
 * <p>
 * Always terminal: a video that is too long will still be too long on the next attempt.
 */
public class MediaValidationException extends RuntimeException {

    private final TranscodeFailureReason reason;

    public MediaValidationException(TranscodeFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TranscodeFailureReason reason() {
        return reason;
    }
}
