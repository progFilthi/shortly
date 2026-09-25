package com.shortly.videoservice.impls;

import com.shortly.videoservice.config.RabbitMQConfig;
import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.CreateS3PresignedUrlResponse;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.enums.VideoStatus;
import com.shortly.videoservice.events.VideoUploadedEvent;
import com.shortly.videoservice.mappers.VideoMapper;
import com.shortly.videoservice.models.Video;
import com.shortly.videoservice.repositories.VideoRepository;
import com.shortly.videoservice.services.S3StorageService;
import com.shortly.videoservice.services.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    @Override
    @Transactional
    public CreateS3PresignedUrlResponse createVideo(CreateS3PresignedUrlRequest request, String userId) {

        //Explicitly pre-generate UUID so s3Key and presignedUrl can be created in a single DB write

        UUID videoId = UUID.randomUUID();

        String fileExtension = determineFileExtension(request.contentType());

        String s3Key = "raw/" + userId + "/" + videoId + fileExtension;

        Video video = videoMapper.toEntity(request, userId, s3Key);

        video.setId(videoId);

        videoRepository.save(video);

        Duration expiration = Duration.ofMinutes(15);

        String uploadUrl = s3StorageService.generatePresignedUploadUrl(s3Key, request.contentType(), expiration);

        return new CreateS3PresignedUrlResponse(
                videoId,
                uploadUrl,
                s3Key,
                Instant.now().plus(expiration)
        );
    }

    @Override
    @Transactional
    public VideoResponse confirmUploadComplete(UUID videoId, String userId) {

        /*
        * Check if video is in DB
        * */
        Video video = videoRepository.findById(videoId)
                .orElseThrow(
                        ()-> new RuntimeException("Video not found! with ID: " + videoId)
                );

        /*
        * Check if video belongs to the right user:
        * */

        if(!video.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized access to update a video: " + videoId);
        }

        video.setVideoStatus(VideoStatus.READY);

        video.setVideoUrl(s3StorageService.getPublicVideoUrl(video.getS3Key()));

        Video updatedVideo = videoRepository.save(video);

        /*
        * PublishEvent to RabbitMQ
        * */

        VideoUploadedEvent event = new VideoUploadedEvent(
                updatedVideo.getId(),
                updatedVideo.getUserId(),
                updatedVideo.getS3Key(),
                updatedVideo.getVideoUrl(),
                updatedVideo.getTitle(),
                updatedVideo.getDescription(),
                updatedVideo.getCreatedAt()

        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.VIDEO_EXCHANGE_NAME,
                RabbitMQConfig.VIDEO_UPLOADED_ROUTING_KEY,
                event
        );

        log.info("Published VideoUploadedEvent to RabbitMQ for videoId: {}", updatedVideo.getId());

        return videoMapper.toResponse(updatedVideo);
    }

    @Override
    @Transactional(readOnly = true)
    public VideoResponse getVideoById(UUID id) {
        /*
         * Check if video is in DB
         * */
        Video video = videoRepository.findById(id)
                .orElseThrow(
                        ()-> new RuntimeException("Video not found! with ID: " + id)
                );

        return videoMapper.toResponse(video);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VideoResponse> getVideosByUserId(String userId) {
        return videoRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(videoMapper::toResponse)
                .toList();
    }

    private String determineFileExtension(String contentType) {
        if(contentType == null){
            return ".mp4";
        }

        return switch (contentType.toLowerCase()){
            case "video/quicktime", "video/mov" -> ".mov";
            case "video/webm" -> ".webm";
            default -> ".mp4";
        };
    }
}
