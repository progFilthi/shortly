package com.shortly.videoservice.repositories;

import com.shortly.videoservice.dto.VideoResponse;
import com.shortly.videoservice.models.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VideoRepository extends JpaRepository<Video, UUID> {

    List<Video> findByUserIdOrderByCreatedAtDesc(String userId);
}
