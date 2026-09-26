package com.shortly.videoservice.exceptions;

import java.util.UUID;

/** No sprite sheet was produced for this video, so there is nothing to pick a frame from. */
public class ThumbnailUnavailableException extends RuntimeException {

    public ThumbnailUnavailableException(UUID videoId) {
        super("Video " + videoId + " has no cover-frame sprite sheet; "
                + "the clip was probably too short to sample");
    }
}
