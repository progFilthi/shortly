package com.shortly.videoservice.controllers;

import com.shortly.videoservice.dto.CreateS3PresignedUrlRequest;
import com.shortly.videoservice.dto.CreateS3PresignedUrlResponse;
import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.services.VideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;


    @PostMapping
    public ResponseEntity<CreateS3PresignedUrlResponse> createVideo(@RequestBody CreateS3PresignedUrlRequest request,
                                                                    @RequestHeader("X-User-Id") String userId){
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(videoService.createVideo(request, userId));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<VideoResponse> confirmUploadComplete(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") String userId
            ){

        return ResponseEntity.ok(
                videoService.confirmUploadComplete(id, userId)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<VideoResponse> getVideoById(@PathVariable UUID id){
        return ResponseEntity.ok(videoService.getVideoById(id));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<VideoResponse>> getVideosByUserId(@PathVariable String userId){
        return ResponseEntity.ok(videoService.getVideosByUserId(userId));
    }

}
