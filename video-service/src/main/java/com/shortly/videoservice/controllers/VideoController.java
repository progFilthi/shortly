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

/** Video endpoints. */
@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    /** Begins an upload. */
    @PostMapping
    public ResponseEntity<CreateS3PresignedUrlResponse> createVideo(
            @Valid @RequestBody CreateS3PresignedUrlRequest request,
            CallerIdentity caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(videoService.createVideo(request, caller.userId()));
    }

    /** Confirms the bytes landed and queues the video for transcoding. 202, not 200: the video is now
     * PROCESSING and may take a minute to become playable. */
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

    /** Public. */
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
