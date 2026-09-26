package com.shortly.videoservice.impls;

import com.shortly.contracts.events.VideoUploadedEvent;
import com.shortly.contracts.messaging.MessagingTopology;
import com.shortly.videoservice.config.VideoStorageProperties;
import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.CreateS3PresignedUrlResponse;
import com.shortly.videoservice.dto.SelectThumbnailRequest;
import com.shortly.videoservice.dto.UploadTargetMetadata;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.enums.VideoStatus;
import com.shortly.videoservice.exceptions.ThumbnailUnavailableException;
import com.shortly.videoservice.exceptions.VideoNotAuthorizedException;
import com.shortly.videoservice.exceptions.VideoNotFoundException;
import com.shortly.videoservice.exceptions.VideoNotReadyException;
import com.shortly.videoservice.mappers.VideoMapper;
import com.shortly.videoservice.models.Video;
import com.shortly.videoservice.repositories.VideoRepository;
import com.shortly.videoservice.services.S3StorageService;
import com.shortly.videoservice.services.UploadVerificationException;
import com.shortly.videoservice.services.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoServiceImpl implements VideoService {

    private final VideoRepository videoRepository;
    private final VideoMapper videoMapper;
    private final S3StorageService s3StorageService;
    private final RabbitTemplate rabbitTemplate;
    private final VideoStorageProperties properties;

    @Override
    @Transactional
    public CreateS3PresignedUrlResponse createVideo(CreateS3PresignedUrlRequest request, String userId) {
        /*
         * The id is generated up front so the database row and the storage key can be written
         * together. Deriving the key from the id later would mean a second round trip and a
         * window in which the row exists with no key.
         */
        UUID videoId = UUID.randomUUID();
        String s3Key = properties.sourceKey(userId, videoId, extensionFor(request.contentType()));

        Video video = videoMapper.toEntity(request, userId, s3Key);
        video.setId(videoId);
        video.setVideoStatus(VideoStatus.UPLOADING);
        videoRepository.save(video);

        String uploadUrl = s3StorageService.generatePresignedUploadUrl(
                s3Key, request.contentType(), properties.presignTtl());

        log.info("Issued upload target for video {} (user {}, key {})", videoId, userId, s3Key);

        return new CreateS3PresignedUrlResponse(
                videoId,
                uploadUrl,
                s3Key,
                Instant.now().plus(properties.presignTtl()),
                properties.maxUploadBytesValue(),
                properties.maxDurationSeconds(),
                properties.preferH264FromClients());
    }

    /**
     * Confirms an upload and hands the video to the transcoder.
     * <p>
     * Three things happen here that did not before, and each one closes a hole:
     * <ol>
     *   <li><b>The object is verified against the bucket.</b> Previously this endpoint trusted
     *       the client's claim that an upload had happened; a caller could mark any videoId
     *       READY without a byte ever being written.</li>
     *   <li><b>The status becomes PROCESSING, not READY.</b> The video is not playable yet,
     *       and publishing READY here is what made {@code PROCESSING} unreachable. READY is now
     *       set only by the transcoder's {@code video.ready} event.</li>
     *   <li><b>The authoritative size travels with the event</b>, so the transcoder is not
     *       asked to re-derive what we already know.</li>
     * </ol>
     */
    @Override
    @Transactional
    public VideoResponse confirmUploadComplete(UUID videoId, String userId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!video.getUserId().equals(userId)) {
            throw new VideoNotAuthorizedException(videoId);
        }

        if (video.getVideoStatus() == VideoStatus.PROCESSING) {
            // Duplicate /complete from a retrying client. Republishing would re-enqueue a
            // transcode the transcoder may already be running; the event carries an id the
            // transcoder deduplicates on anyway, but not doing the work twice is cheaper.
            log.info("Video {} is already PROCESSING; acknowledging without republishing", videoId);
            return videoMapper.toResponse(video);
        }

        if (video.getVideoStatus() == VideoStatus.READY) {
            log.info("Video {} is already READY; acknowledging without republishing", videoId);
            return videoMapper.toResponse(video);
        }

        UploadTargetMetadata uploaded;
        try {
            uploaded = s3StorageService.verifyUploadedObject(video.getS3Key());
        } catch (UploadVerificationException e) {
            /*
             * The upload is not salvageable, so the row must not sit in UPLOADING forever
             * waiting for a job that will never be enqueued. Mark it FAILED and, for an
             * oversized object, delete it immediately so the bytes stop costing money.
             */
            video.setVideoStatus(VideoStatus.FAILED);
            video.setFailureReason("UPLOAD_" + e.kind().name());
            video.setFailureMessage(truncate(e.getMessage()));
            videoRepository.save(video);

            if (e.kind() == UploadVerificationException.Kind.TOO_LARGE) {
                s3StorageService.deleteObject(video.getS3Key());
            }
            throw e;
        }

        video.setVideoStatus(VideoStatus.PROCESSING);
        video.setSourceBytes(uploaded.contentLength());
        video.setSourceContentType(uploaded.contentType());
        video.setFailureReason(null);
        video.setFailureMessage(null);
        Video saved = videoRepository.save(video);

        rabbitTemplate.convertAndSend(
                MessagingTopology.VIDEO_EXCHANGE,
                MessagingTopology.VIDEO_UPLOADED_ROUTING_KEY,
                new VideoUploadedEvent(
                        saved.getId(),
                        saved.getUserId(),
                        saved.getS3Key(),
                        uploaded.contentType(),
                        uploaded.contentLength(),
                        Instant.now()));

        log.info("Video {} verified ({} bytes) and queued for processing", videoId,
                uploaded.contentLength());

        return videoMapper.toResponse(saved);
    }

    /**
     * Records the cover frame the user picked from the sprite sheet.
     * <p>
     * The client sends a tile index, not a URL or a timestamp: the sheet is immutable for a
     * given video, so an index is the only identifier that cannot be forged into a URL pointing
     * somewhere else.
     */
    @Override
    @Transactional
    public VideoResponse selectThumbnail(UUID videoId, SelectThumbnailRequest request, String userId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!video.getUserId().equals(userId)) {
            throw new VideoNotAuthorizedException(videoId);
        }

        if (video.getVideoStatus() != VideoStatus.READY) {
            throw new VideoNotReadyException(videoId, video.getVideoStatus());
        }

        if (video.getThumbnailSpriteUrl() == null) {
            throw new ThumbnailUnavailableException(videoId);
        }

        video.setThumbnailTileIndex(request.tileIndex());
        Video saved = videoRepository.save(video);

        log.info("Video {} cover set to sprite tile {}", videoId, request.tileIndex());
        return videoMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public VideoResponse getVideoById(UUID id) {
        return videoMapper.toResponse(videoRepository.findById(id)
                .orElseThrow(() -> new VideoNotFoundException(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VideoResponse> getVideosByUserId(String userId) {
        return videoRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(videoMapper::toResponse)
                .toList();
    }

    /**
     * Maps a declared MIME type to a storage suffix.
     * <p>
     * The request is validated against a video allow-list before reaching here, so an unknown
     * type is a programming error rather than untrusted input. {@code .mp4} is still the
     * fallback so an unmapped-but-valid type cannot produce a key with no extension.
     */
    private String extensionFor(String contentType) {
        if (contentType == null) {
            return ".mp4";
        }
        return switch (contentType.toLowerCase()) {
            case "video/quicktime" -> ".mov";
            case "video/x-m4v" -> ".m4v";
            case "video/webm" -> ".webm";
            case "video/x-matroska" -> ".mkv";
            default -> ".mp4";
        };
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
