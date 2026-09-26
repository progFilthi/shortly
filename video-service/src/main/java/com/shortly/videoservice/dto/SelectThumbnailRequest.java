package com.shortly.videoservice.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * The user's cover-frame choice, as a tile index into the video's sprite sheet.
 * <p>
 * An index rather than a URL or a timestamp. The sheet is immutable for a given video, so an
 * index is the only form that cannot be used to point the platform at an arbitrary location.
 *
 * @param tileIndex zero-based row-major index into the sprite sheet grid
 */
public record SelectThumbnailRequest(
        @PositiveOrZero(message = "tileIndex must be zero or greater")
        int tileIndex
) {
}
