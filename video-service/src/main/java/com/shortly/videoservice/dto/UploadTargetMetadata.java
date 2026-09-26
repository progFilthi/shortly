package com.shortly.videoservice.dto;

/**
 * Authoritative facts about an uploaded object, read back from object storage.
 * <p>
 * Every field here comes from {@code HeadObject}, never from the client. The client's
 * declared content type is recorded separately and treated as a hint only.
 *
 * @param s3Key         key that was verified
 * @param contentLength size in bytes, from the bucket
 * @param contentType   what the client actually declared, or null if it declared none
 * @param eTag          entity tag, useful for cache validation and change detection
 * @param lastModified  when the bucket last saw a write to this key
 */
public record UploadTargetMetadata(
        String s3Key,
        long contentLength,
        String contentType,
        String eTag,
        java.time.Instant lastModified
) {
}
