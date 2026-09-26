package com.shortly.videoservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to begin an upload.
 * <p>
 * {@code contentType} is constrained to a video allow-list here rather than trusted. It is
 * used for two things - picking the s3 key suffix and being embedded in the presigned
 * signature - so an unchecked value would let a caller store arbitrary bytes at a {@code .mp4}
 * key and have the platform advertise it as a video.
 *
 * @param title       display title
 * @param description optional longer text
 * @param contentType MIME type of the bytes about to be uploaded
 */
public record CreateS3PresignedUrlRequest(

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be at most 200 characters")
        String title,

        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description,

        @NotBlank(message = "contentType is required")
        @Pattern(
                regexp = "(?i)video/(mp4|quicktime|x-m4v|webm|x-matroska)",
                message = "contentType must be one of video/mp4, video/quicktime, "
                        + "video/x-m4v, video/webm, video/x-matroska")
        String contentType
) {
}
