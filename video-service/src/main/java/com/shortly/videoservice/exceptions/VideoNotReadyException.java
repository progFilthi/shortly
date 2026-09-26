package com.shortly.videoservice.exceptions;

import com.shortly.videoservice.enums.VideoStatus;

import java.util.UUID;

/** The operation needs a playable video, and this one is still being processed or has failed. */
public class VideoNotReadyException extends RuntimeException {

    private final VideoStatus status;

    public VideoNotReadyException(UUID videoId, VideoStatus status) {
        super("Video " + videoId + " is not ready (current status: " + status + ")");
        this.status = status;
    }

    public VideoStatus status() {
        return status;
    }
}
