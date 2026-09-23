package com.shortly.videoservice.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateS3PresignedUrlResponse(
        UUID videoId,
        String uploadUrl,
        String s3Key,
        Instant expiresAt

) {
}
