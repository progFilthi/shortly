package com.shortly.videoservice.dto;

import com.shortly.videoservice.enums.VideoStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record VideoResponse(
        UUID id,
        String title,
        String description,
        String uploadUrl,
        String userId,
        VideoStatus videoStatus,
        LocalDateTime createdAt
) {
}
