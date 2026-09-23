package com.shortly.videoservice.services;

import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.CreateS3PresignedUrlResponse;
import com.shortly.videoservice.dto.VideoResponse;

import java.util.List;
import java.util.UUID;

public interface VideoService {

    CreateS3PresignedUrlResponse createVideo(CreateS3PresignedUrlRequest request, String userId);
    VideoResponse confirmUploadComplete(UUID videoId, String userId);
    VideoResponse getVideoById(UUID id);
    List<VideoResponse> getVideosByUserId(String  userId);
}
