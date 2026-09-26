package com.shortly.contracts.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by video-service once an upload has been verified in object storage and is
 * ready for processing.
 * <p>
 * Carries only what a transcoder needs. Notably it does NOT carry title/description: those
 * are owned by video-service's database and consumers that want them read the database or
 * subscribe to their own event, keeping this payload minimal.
 *
 * @param videoId       primary key of the video aggregate
 * @param userId        owning user, as an opaque cross-service identifier
 * @param s3Key         bucket-relative key of the uploaded original
 * @param contentType   declared content type, used only as a hint; the real container is
 *                      determined by probing the bytes
 * @param fileSizeBytes authoritative size from {@code HeadObject}, not client-asserted
 * @param uploadedAt    when the upload was confirmed
 */
public record VideoUploadedEvent(
        UUID videoId,
        String userId,
        String s3Key,
        String contentType,
        long fileSizeBytes,
        Instant uploadedAt
) {
}
