package com.shortly.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** Published by video-service once an upload has been verified in object storage and is ready for
 * processing. Carries only what a transcoder needs. */
public record VideoUploadedEvent(
        UUID videoId,
        String userId,
        String s3Key,
        String contentType,
        long fileSizeBytes,
        Instant uploadedAt
) {
}
