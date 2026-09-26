package com.shortly.transcoderservice.transcode;

/** ffmpeg ran to completion but exited non-zero, or produced no usable ladder. */
public class TranscodeFailedException extends RuntimeException {

    public TranscodeFailedException(String message) {
        super(message);
    }
}
