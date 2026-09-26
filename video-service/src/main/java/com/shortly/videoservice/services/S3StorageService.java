package com.shortly.videoservice.services;

import com.shortly.videoservice.dto.UploadTargetMetadata;

import java.time.Duration;

/** Object storage operations the video service needs. */
public interface S3StorageService {

    String generatePresignedUploadUrl(String s3Key, String contentType, Duration expiration);

    /** Checks that the object exists and is within the platform's limits. */
    UploadTargetMetadata verifyUploadedObject(String s3Key);

    /** Removes an object that failed verification, so it cannot be retried or served. */
    void deleteObject(String s3Key);

    String getPublicVideoUrl(String s3Key);
}
