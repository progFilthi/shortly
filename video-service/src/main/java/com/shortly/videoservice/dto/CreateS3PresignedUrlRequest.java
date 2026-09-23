package com.shortly.videoservice.dto;

public record CreateS3PresignedUrlRequest(
        String title,
        String description,
        String contentType
) {
}
