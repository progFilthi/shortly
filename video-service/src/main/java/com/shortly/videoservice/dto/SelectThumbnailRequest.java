package com.shortly.videoservice.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** The user's cover-frame choice, as a tile index into the video's sprite sheet. */
public record SelectThumbnailRequest(
        @PositiveOrZero(message = "tileIndex must be zero or greater")
        int tileIndex
) {
}
