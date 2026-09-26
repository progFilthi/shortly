package com.shortly.contracts.events;

import com.shortly.contracts.media.TranscodeFailureReason;

import java.time.Instant;
import java.util.UUID;

/** Published by transcoder-service when a video can never become playable. */
public record VideoFailedEvent(
        UUID videoId,
        String userId,
        TranscodeFailureReason reason,
        String message,
        boolean retryable,
        Instant failedAt
) {
}
