package com.shortly.videoservice.exceptions;

import java.util.UUID;

public class VideoNotFoundException extends RuntimeException {

    public VideoNotFoundException(UUID videoId) {
        super("No video found with id " + videoId);
    }
}
