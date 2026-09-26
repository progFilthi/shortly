package com.shortly.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by transcoder-service when an HLS ladder has been fully produced and uploaded.
 * <p>
 * Only published after every media playlist has been uploaded, so a consumer that reacts to
 * this event can never observe a partially populated ladder.
 *
 * @param videoId            the video this ladder belongs to
 * @param userId             owning user, copied through so consumers need not query back
 * @param hlsManifestUrl     absolute URL of {@code master.m3u8} - the only URL a player needs
 * @param posterUrl          absolute URL of the generated poster JPEG, used as feed cover
 * @param spriteSheetUrl     absolute URL of the thumbnail sprite sheet for client-side scrubbing
 * @param spriteFrameCount    number of frames tiled into the sprite sheet
 * @param spriteColumns      grid columns in the sprite sheet, needed to map a time to a tile
 * @param durationSeconds    probed duration of the source
 * @param width              output width (canonical aspect transform applied)
 * @param height             output height
 * @param renditions         ladder actually produced, which may be a subset of the configured ladder
 * @param processedAt        when the ladder became durable
 */
public record VideoReadyEvent(
        UUID videoId,
        String userId,
        String hlsManifestUrl,
        String posterUrl,
        String spriteSheetUrl,
        int spriteFrameCount,
        int spriteColumns,
        int durationSeconds,
        int width,
        int height,
        List<RenditionInfo> renditions,
        Instant processedAt
) {
}
