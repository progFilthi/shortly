package com.shortly.videoservice.exceptions;

import java.util.UUID;

/** The caller does not own the video. Surfaces as 403, never 404, so ownership is not probed. */
public class VideoNotAuthorizedException extends RuntimeException {

    public VideoNotAuthorizedException(UUID videoId) {
        super("Not authorized to modify video " + videoId);
    }
}
