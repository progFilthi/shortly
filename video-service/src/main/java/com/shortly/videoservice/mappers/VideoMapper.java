package com.shortly.videoservice.mappers;

import com.shortly.videoservice.config.VideoStorageProperties;
import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.models.Video;

import java.util.List;

public interface VideoMapper {

    Video toEntity(CreateS3PresignedUrlRequest request, String userId, String s3Key);

    VideoResponse toResponse(Video video);

    /**
     * Builds the per-rendition media playlist URLs.
     * <p>
     * Derived from the manifest URL rather than stored, so the two can never drift apart. The
     * manifest always ends in {@code /master.m3u8} and a variant always sits beside it as
     * {@code /<rung>/index.m3u8}, both of which this service controls.
     */
    static List<VideoResponse.RenditionResponse> toRenditionResponses(Video video) {
        if (video.getRenditions() == null || video.getRenditions().isEmpty()) {
            return List.of();
        }
        String manifestUrl = video.getHlsManifestUrl() != null
                ? video.getHlsManifestUrl()
                : video.getVideoUrl();
        if (manifestUrl == null) {
            return List.of();
        }
        String base = manifestUrl.endsWith("/master.m3u8")
                ? manifestUrl.substring(0, manifestUrl.length() - "/master.m3u8".length())
                : manifestUrl;

        return video.getRenditions().stream()
                .map(rendition -> new VideoResponse.RenditionResponse(
                        rendition.name(),
                        rendition.width(),
                        rendition.height(),
                        rendition.videoKbps(),
                        base + "/" + rendition.name() + "/index.m3u8"))
                .toList();
    }
}
