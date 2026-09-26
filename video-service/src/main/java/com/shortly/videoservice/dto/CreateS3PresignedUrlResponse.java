package com.shortly.videoservice.dto;

import java.time.Instant;
import java.util.UUID;

/** Everything the client needs to perform one upload. @param videoId pre-generated id, so the row
 * the caller should normalise on device before uploading */
public record CreateS3PresignedUrlResponse(
        UUID videoId,
        String uploadUrl,
        String s3Key,
        Instant expiresAt,
        long maxBytes,
        int maxDurationSeconds,
        boolean requiresH264
) {
}
