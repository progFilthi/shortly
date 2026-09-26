package com.shortly.videoservice.messaging;

import com.shortly.contracts.events.VideoFailedEvent;
import com.shortly.contracts.events.VideoReadyEvent;
import com.shortly.contracts.messaging.MessagingTopology;
import com.shortly.videoservice.enums.VideoStatus;
import com.shortly.videoservice.models.Video;
import com.shortly.videoservice.repositories.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies transcoder outcomes to the video aggregate.
 * <p>
 * video-service owns the {@code videos} table; the transcoder never writes to it. It publishes
 * {@code video.ready} or {@code video.failed} and this listener applies the transition. That
 * keeps the two services independently deployable and means a transcoder outage or a rogue
 * write can never corrupt video metadata.
 * <p>
 * Both handlers are idempotent. Events can be redelivered, and re-applying a transition that
 * has already happened must be a no-op rather than an error - otherwise a duplicate publish
 * dead-letters a video that is already perfectly fine.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoProcessingListener {

    private final VideoRepository videoRepository;

    @RabbitListener(
            queues = MessagingTopology.VIDEO_READY_QUEUE,
            containerFactory = "videoListenerContainerFactory")
    @Transactional
    public void onVideoReady(VideoReadyEvent event) {
        Video video = videoRepository.findById(event.videoId()).orElse(null);
        if (video == null) {
            // Nothing to attach the ladder to. Not retryable: a missing row will still be
            // missing next time, and looping on it would mask a much larger bug.
            log.error("Received video.ready for {} but no such video exists; dropping", event.videoId());
            return;
        }

        if (video.getVideoStatus() == VideoStatus.READY) {
            log.debug("Video {} is already READY; ignoring duplicate ready event", event.videoId());
            return;
        }

        if (video.getVideoStatus() == VideoStatus.FAILED) {
            // A late success after a recorded failure. Trust the failure: it was published for
            // a reason, and silently flipping a FAILED video to READY would hide it.
            log.warn("Received video.ready for {} which is already FAILED ({}); ignoring",
                    event.videoId(), video.getFailureReason());
            return;
        }

        video.setVideoStatus(VideoStatus.READY);
        video.setVideoUrl(event.hlsManifestUrl());
        video.setHlsManifestUrl(event.hlsManifestUrl());
        video.setPosterUrl(event.posterUrl());
        video.setThumbnailSpriteUrl(event.spriteSheetUrl());
        video.setDurationSeconds(event.durationSeconds());
        video.setWidth(event.width());
        video.setHeight(event.height());
        video.setRenditions(event.renditions());
        video.setFailureReason(null);
        video.setFailureMessage(null);

        try {
            videoRepository.save(video);
        } catch (OptimisticLockingFailureException e) {
            // Another writer (a cover-frame selection, say) touched the row concurrently.
            // The event will be redelivered, and re-applying it is safe.
            log.warn("Concurrent write while marking video {} READY; will retry", event.videoId());
            throw e;
        }

        log.info("Video {} is READY: {} ({} rendition(s), {}s, {}x{})",
                event.videoId(), event.hlsManifestUrl(), event.renditions().size(),
                event.durationSeconds(), event.width(), event.height());
    }

    @RabbitListener(
            queues = MessagingTopology.VIDEO_FAILED_QUEUE,
            containerFactory = "videoListenerContainerFactory")
    @Transactional
    public void onVideoFailed(VideoFailedEvent event) {
        Video video = videoRepository.findById(event.videoId()).orElse(null);
        if (video == null) {
            log.error("Received video.failed for {} but no such video exists; dropping", event.videoId());
            return;
        }

        if (video.getVideoStatus() == VideoStatus.READY) {
            log.warn("Received video.failed for {} which is already READY; ignoring",
                    event.videoId());
            return;
        }

        video.setVideoStatus(VideoStatus.FAILED);
        video.setFailureReason(event.reason() == null ? null : event.reason().name());
        video.setFailureMessage(truncate(event.message()));

        videoRepository.save(video);
        log.info("Video {} is FAILED: {} ({})", event.videoId(), event.reason(), event.message());
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
