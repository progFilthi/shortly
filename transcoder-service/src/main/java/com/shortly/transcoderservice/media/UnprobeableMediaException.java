package com.shortly.transcoderservice.media;

/** The uploaded file exists but cannot be interpreted as media. Terminal, never retryable. */
public class UnprobeableMediaException extends RuntimeException {

    public UnprobeableMediaException(String message) {
        super(message);
    }

    public UnprobeableMediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
