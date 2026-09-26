package com.shortly.transcoderservice.media;

import com.shortly.contracts.media.TranscodeFailureReason;

/** The input violates a platform limit. */
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
