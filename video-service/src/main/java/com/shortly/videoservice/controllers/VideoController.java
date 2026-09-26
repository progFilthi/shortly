package com.shortly.videoservice.controllers;

import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.CreateS3PresignedUrlResponse;
import com.shortly.videoservice.dto.SelectThumbnailRequest;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.security.CallerIdentity;
import com.shortly.videoservice.services.VideoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Video endpoints.
 *
 * <p>The caller is a {@link CallerIdentity} parameter, resolved from the gateway-asserted
 * {@code X-User-Id}. No controller in this service reads that header directly, so none of them can
 * be reached without a verified gateway secret.
 */
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    /**
     * Begins an upload.
     * <p>
     * {@code @Valid} is what activates the content-type allow-list and the length limits. Without
     * it the constraints on the record are documentation.
     */
    @PostMapping
    public ResponseEntity<CreateS3PresignedUrlResponse> createVideo(
            @Valid @RequestBody CreateS3PresignedUrlRequest request,
            CallerIdentity caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(videoService.createVideo(request, caller.userId()));
    }

    /**
     * Confirms the bytes landed and queues the video for transcoding.
     * <p>
     * 202, not 200: the video is now PROCESSING and may take a minute to become playable. A 200
     * would tell the client it can play it now.
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<VideoResponse> confirmUploadComplete(
            @PathVariable UUID id,
            CallerIdentity caller) {
        return ResponseEntity.accepted()
                .body(videoService.confirmUploadComplete(id, caller.userId()));
    }

    /** Records the cover frame the user picked from the sprite sheet. */
    @PutMapping("/{id}/thumbnail")
    public ResponseEntity<VideoResponse> selectThumbnail(
            @PathVariable UUID id,
            @Valid @RequestBody SelectThumbnailRequest request,
            CallerIdentity caller) {
        return ResponseEntity.ok(videoService.selectThumbnail(id, request, caller.userId()));
    }

    /**
     * Public. A feed has to render before a viewer is necessarily signed in, and the playback URL
     * it returns is already an unauthenticated CDN URL.
     */
    @GetMapping("/{id}")
    public ResponseEntity<VideoResponse> getVideoById(@PathVariable UUID id) {
        return ResponseEntity.ok(videoService.getVideoById(id));
    }

    /** Public for the same reason as {@link #getVideoById}. */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<VideoResponse>> getVideosByUserId(@PathVariable String userId) {
        return ResponseEntity.ok(videoService.getVideosByUserId(userId));
    }
}
