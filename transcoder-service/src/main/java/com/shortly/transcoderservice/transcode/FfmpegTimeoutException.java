package com.shortly.transcoderservice.transcode;

/** ffmpeg ran but exceeded its wall-clock budget. Retryable on a larger instance. */
public class FfmpegTimeoutException extends RuntimeException {

    public FfmpegTimeoutException(String message) {
        super(message);
    }
}
