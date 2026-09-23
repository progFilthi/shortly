package com.shortly.videoservice.services;

import java.time.Duration;

public interface S3StorageService {
    String generatePresignedUploadUrl(String s3Key, String contentType, Duration expiration);
    String getPublicVideoUrl(String  s3Key);
}
