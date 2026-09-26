package com.shortly.videoservice.dto;

import com.shortly.videoservice.enums.VideoStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A video as returned to clients.
 * <p>
 * Note the field is {@code playbackUrl} and not {@code uploadUrl}. The old name was fed from
 * {@code video.videoUrl} - the playback location - and the iOS client has been decoding
 * {@code uploadUrl} as its playback source all along. Renamed to match what it actually is;
 * see the migration note in the API docs before removing the old key.
 *
 * @param playbackUrl          HLS master manifest URL, or the raw CDN URL before processing
 * @param posterUrl            generated cover image, available once READY
 * @param thumbnailSpriteUrl   sprite sheet for client-side cover selection
 * @param thumbnailTileIndex   cover frame the user selected, or null for the default poster
 * @param renditions           adaptive ladder actually produced
 */
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

    /**
     * One rung of the adaptive ladder.
     *
     * @param playlistUrl absolute URL of this rendition's media playlist
     */
    public record RenditionResponse(
            String name,
            int width,
            int height,
            int videoKbps,
            String playlistUrl
    ) {
    }
}
