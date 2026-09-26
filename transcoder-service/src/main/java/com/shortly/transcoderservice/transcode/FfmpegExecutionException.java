package com.shortly.transcoderservice.transcode;

/** ffmpeg could not be started, was interrupted, or produced no output where one was required. */
public class FfmpegExecutionException extends RuntimeException {

    public FfmpegExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public FfmpegExecutionException(String message) {
        super(message);
    }
}
