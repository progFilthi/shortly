package com.shortly.videoservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything the client needs to perform one upload.
 * <p>
 * The limits are echoed back rather than left implicit so the client can enforce them before
 * starting a transfer, instead of discovering them after spending the bandwidth.
 *
 * @param videoId       pre-generated id, so the row and the key are written in one insert
 * @param uploadUrl     presigned URL; single PUT, not multipart
 * @param s3Key         bucket-relative destination key
 * @param expiresAt     when the presigned URL stops working
 * @param maxBytes      hard ceiling the server will accept; the client must not exceed it
 * @param maxDurationSeconds longest clip the platform will transcode
 * @param requiresH264  whether the caller should normalise on device before uploading
 */
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
