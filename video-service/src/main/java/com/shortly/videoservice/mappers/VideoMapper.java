package com.shortly.videoservice.mappers;

import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.models.Video;

public interface VideoMapper {

    Video toEntity(CreateS3PresignedUrlRequest request, String userId, String s3Key);

    VideoResponse toResponse(Video video);

}
