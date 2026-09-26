package com.shortly.transcoderservice.listener;

import com.shortly.contracts.events.VideoFailedEvent;
import com.shortly.contracts.events.VideoReadyEvent;
import com.shortly.contracts.events.VideoUploadedEvent;
import com.shortly.contracts.media.TranscodeFailureReason;
import com.shortly.contracts.messaging.MessagingTopology;
import com.shortly.transcoderservice.storage.TranscoderObjectStore;
import com.shortly.transcoderservice.storage.TranscoderStorageProperties;
import com.shortly.transcoderservice.transcode.TranscodePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Consumes {@link VideoUploadedEvent} and drives one video to {@code READY} or {@code FAILED}.
 *
 * <p><b>Who owns the row.</b> This service never writes to video-service's database. It publishes
 * an outcome and lets video-service, which owns the aggregate, apply the transition - so a
 * transcoder outage cannot corrupt video metadata and the two deploy independently.
 *
 * <p><b>No manual acking.</b> The container acks on normal return and the queue has a
 * dead-letter chain, so an exhausted retry budget lands in the DLQ rather than being lost or
 * hot-looping. Non-terminal failures are rethrown so the interceptor can retry; terminal ones are
 * published and swallowed, because retrying a 70-second video never succeeds.
 */
@Component
public class VideoUploadedListener {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadedListener.class);

    private final TranscodePipeline pipeline;
    private final TranscoderObjectStore objectStore;
    private final TranscoderStorageProperties storage;
    private final RabbitTemplate rabbitTemplate;

    public VideoUploadedListener(TranscodePipeline pipeline,
                                 TranscoderObjectStore objectStore,
                                 TranscoderStorageProperties storage,
                                 RabbitTemplate rabbitTemplate) {
        this.pipeline = pipeline;
        this.objectStore = objectStore;
        this.storage = storage;
        this.rabbitTemplate = rabbitTemplate;
    }

    @org.springframework.amqp.rabbit.annotation.RabbitListener(
            queues = MessagingTopology.VIDEO_UPLOADED_QUEUE,
            containerFactory = "transcoderListenerContainerFactory")
    public void onVideoUploaded(VideoUploadedEvent event) {
        log.info("Received upload for video {} (user {}, key {}, {} bytes)",
                event.videoId(), event.userId(), event.s3Key(), event.fileSizeBytes());

        /*
         * Idempotency. Redelivery is normal - a consumer crash mid-job, a broker restart, a
         * redeploy - and re-running a 6-rung transcode is expensive enough to be worth
         * avoiding. The presence of the master manifest is the authoritative signal that the
         * ladder completed, because it is only ever uploaded after every segment is durable.
         */
        String manifestKey = storage.manifestKey(event.videoId());
        if (objectStore.exists(manifestKey)) {
            log.info("Ladder for video {} already exists at {}; skipping re-encode",
                    event.videoId(), manifestKey);
            republishReadyFromExistingOutput(event);
            return;
        }

        try {
            VideoReadyEvent ready = withOwner(
                    pipeline.process(event.videoId(), event.s3Key(), event.fileSizeBytes()),
                    event.userId());
            publish(ready);
            log.info("Video {} is READY at {}", event.videoId(), ready.hlsManifestUrl());

        } catch (RuntimeException e) {
            TranscodeFailureReason reason = TranscodePipeline.classify(e);

            if (reason.isRetryable()) {
                // Rethrow so the container's retry interceptor gets a chance. The message is
                // only dead-lettered once that budget is spent.
                log.error("Retryable failure processing video {} ({}); will retry",
                        event.videoId(), reason, e);
                throw e;
            }

            // Terminal. Recorded against the video, then swallowed: requeueing would spin
            // forever on an input that is permanently invalid.
            log.error("Video {} failed permanently ({}): {}", event.videoId(), reason, e.getMessage());
            publish(new VideoFailedEvent(
                    event.videoId(), event.userId(), reason, safeMessage(e), false, Instant.now()));
        }
    }

    /**
     * Re-publishes readiness for a ladder that already exists, from the sidecar the original job
     * wrote. That sidecar is what makes this exact: without it the path would have to re-parse
     * every media playlist or republish guessed metadata.
     */
    private void republishReadyFromExistingOutput(VideoUploadedEvent event) {
        Optional<VideoReadyEvent> stored = objectStore.readSidecar(
                storage.readyEventKey(event.videoId()), VideoReadyEvent.class);

        if (stored.isPresent()) {
            publish(stored.get());
            log.info("Re-published readiness for video {} from stored sidecar", event.videoId());
            return;
        }

        // The manifest exists but the sidecar does not, which means an older build produced
        // this ladder. Publish the minimum a client can act on rather than dropping the event:
        // the manifest URL alone is enough to play, and the feed can fill in the rest.
        log.warn("Video {} has a ladder but no sidecar; publishing manifest URL only",
                event.videoId());
        publish(new VideoReadyEvent(
                event.videoId(),
                event.userId(),
                storage.cdnUrl(storage.manifestKey(event.videoId())),
                null, null, 0, 0, 0, 0, 0,
                java.util.List.of(),
                Instant.now()));
    }

    private VideoReadyEvent withOwner(VideoReadyEvent ready, String userId) {
        return new VideoReadyEvent(
                ready.videoId(), userId, ready.hlsManifestUrl(), ready.posterUrl(),
                ready.spriteSheetUrl(), ready.spriteFrameCount(), ready.spriteColumns(),
                ready.durationSeconds(), ready.width(), ready.height(),
                ready.renditions(), ready.processedAt());
    }

    private void publish(VideoReadyEvent event) {
        rabbitTemplate.convertAndSend(
                MessagingTopology.VIDEO_EXCHANGE,
                MessagingTopology.VIDEO_READY_ROUTING_KEY,
                event);
    }

    private void publish(VideoFailedEvent event) {
        rabbitTemplate.convertAndSend(
                MessagingTopology.VIDEO_EXCHANGE,
                MessagingTopology.VIDEO_FAILED_ROUTING_KEY,
                event);
    }

    /**
     * ffmpeg's stderr is attacker-influenced. The full text goes to the log; the event
     * carries only the first line, which is enough for a support conversation and safe to
     * store in a column a human may read.
     */
    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return error.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        String firstLine = newline > 0 ? message.substring(0, newline) : message;
        return firstLine.length() > 300 ? firstLine.substring(0, 300) + "..." : firstLine;
    }
}
