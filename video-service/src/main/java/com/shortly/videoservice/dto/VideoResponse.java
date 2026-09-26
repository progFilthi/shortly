package com.shortly.videoservice.dto;

import com.shortly.videoservice.enums.VideoStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A video as returned to clients. Note the field is {@code playbackUrl} and not {@code uploadUrl}. */
public record VideoResponse(
        UUID id,
        String title,
        String description,
        String playbackUrl,
        String posterUrl,
        String thumbnailSpriteUrl,
        Integer thumbnailTileIndex,
        Integer durationSeconds,
        Integer width,
        Integer height,
        Long sourceBytes,
        String userId,
        VideoStatus videoStatus,
        String failureReason,
        String failureMessage,
        LocalDateTime createdAt,
        List<RenditionResponse> renditions
) {

    /** One rung of the adaptive ladder. */
    public record RenditionResponse(
            String name,
            int width,
            int height,
            int videoKbps,
            String playlistUrl
    ) {
    }
}
