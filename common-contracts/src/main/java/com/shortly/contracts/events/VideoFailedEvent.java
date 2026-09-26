package com.shortly.contracts.events;

import com.shortly.contracts.media.TranscodeFailureReason;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by transcoder-service when a video can never become playable.
 * <p>
 * Always terminal from the platform's point of view: a non-retryable reason is recorded
 * against the video and the job is dead-lettered. A retryable reason (transient object-storage
 * or ffmpeg failure) is published only after the in-process retry budget is exhausted.
 *
 * @param videoId  the video that failed
 * @param userId   owning user
 * @param reason   machine-readable cause; clients should switch on this
 * @param message  human-readable detail, safe to log but not to show verbatim to end users
 * @param retryable whether re-running the job could plausibly succeed
 * @param failedAt when processing gave up
 */
public record VideoFailedEvent(
        UUID videoId,
        String userId,
        TranscodeFailureReason reason,
        String message,
        boolean retryable,
        Instant failedAt
) {
}
