package com.shortly.videoservice.dto;

/** Authoritative facts about an uploaded object, read back from object storage. Every field here
 * comes from {@code HeadObject}, never from the client. */
public record UploadTargetMetadata(
        String s3Key,
        long contentLength,
        String contentType,
        String eTag,
        java.time.Instant lastModified
) {
}
