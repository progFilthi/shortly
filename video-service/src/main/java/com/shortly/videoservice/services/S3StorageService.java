package com.shortly.videoservice.services;

import com.shortly.videoservice.dto.UploadTargetMetadata;

import java.time.Duration;

/**
 * Object storage operations the video service needs.
 * <p>
 * The interesting addition is {@link #verifyUploadedObject}. A presigned PUT cannot constrain
 * the size of what a client uploads - that requires a presigned POST policy, which AWS SDK v2
 * does not implement - so the only way to know what actually landed is to ask the bucket after
 * the fact. Without that check a client could upload anything at all and the row would go
 * straight to READY at a permanent public CDN URL.
 */
public interface S3StorageService {

    String generatePresignedUploadUrl(String s3Key, String contentType, Duration expiration);

    /**
     * Checks that the object exists and is within the platform's limits.
     *
     * @throws com.shortly.videoservice.services.UploadVerificationException if the object is
     *         absent, empty, or larger than the configured ceiling
     */
    UploadTargetMetadata verifyUploadedObject(String s3Key);

    /** Removes an object that failed verification, so it cannot be retried or served. */
    void deleteObject(String s3Key);

    String getPublicVideoUrl(String s3Key);
}
