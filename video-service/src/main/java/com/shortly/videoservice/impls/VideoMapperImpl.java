package com.shortly.videoservice.impls;

import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.mappers.VideoMapper;
import com.shortly.videoservice.models.Video;
import org.springframework.stereotype.Component;

@Component
public class VideoMapperImpl implements VideoMapper {

    @Override
    public Video toEntity(CreateS3PresignedUrlRequest request, String userId, String s3Key) {
        return Video.builder()
                .title(request.title())
                .description(request.description())
                .s3Key(s3Key)
                .userId(userId)
                .build();
    }

    @Override
    public VideoResponse toResponse(Video video) {
        return new VideoResponse(
                video.getId(),
                video.getTitle(),
                video.getDescription(),
                // Before the ladder exists, fall back to the raw object so a client that opens
                // the video immediately after upload still gets something playable.
                video.getHlsManifestUrl() != null ? video.getHlsManifestUrl() : video.getVideoUrl(),
                video.getPosterUrl(),
                video.getThumbnailSpriteUrl(),
                video.getThumbnailTileIndex(),
                video.getDurationSeconds(),
                video.getWidth(),
                video.getHeight(),
                video.getSourceBytes(),
                video.getUserId(),
                video.getVideoStatus(),
                video.getFailureReason(),
                video.getFailureMessage(),
                video.getCreatedAt(),
                VideoMapper.toRenditionResponses(video)
        );
    }
}
