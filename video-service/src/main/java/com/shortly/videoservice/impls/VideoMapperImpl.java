package com.shortly.videoservice.impls;

import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.enums.VideoStatus;
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
                .videoStatus(VideoStatus.PENDING)
                .build();
    }


    @Override
    public VideoResponse toResponse(Video video) {
        return new VideoResponse(
                video.getId(),
                video.getTitle(),
                video.getDescription(),
                video.getVideoUrl(),
                video.getUserId(),
                video.getVideoStatus(),
                video.getCreatedAt()

        );
    }
}
