package com.shortly.videoservice.events;

import java.time.LocalDateTime;
import java.util.UUID;

public record VideoUploadedEvent(
        UUID videoId,
        String userId,
        String s3Key,
        String videoUrl,
        String title,
        String description,
        LocalDateTime createdAt

) {
}
