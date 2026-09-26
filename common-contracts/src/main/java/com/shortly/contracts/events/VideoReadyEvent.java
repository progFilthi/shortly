package com.shortly.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published by transcoder-service when an HLS ladder has been fully produced and uploaded. @param
 * configured ladder @param processedAt when the ladder became durable */
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
