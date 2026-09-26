package com.shortly.videoservice.models;

import com.shortly.contracts.events.RenditionInfo;
import com.shortly.videoservice.converters.RenditionListConverter;
import com.shortly.videoservice.enums.VideoStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "videos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Video implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    /** Was a bare {@code String}, which Hibernate mapped to {@code varchar(255)} and silently truncated
     * longer descriptions. */
    @Column(length = 2000)
    private String description;

    /** Playback URL, pointing at {@code master.m3u8} once the ladder exists. Not populated at upload-
     * verified to exist. */
    @Column(length = 1024)
    private String videoUrl;

    @Column(length = 512)
    private String s3Key;

    @Column(nullable = false, length = 128)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VideoStatus videoStatus;

    /* ------------------------- populated by the transcoder ------------------------ */

    /** Absolute URL of the HLS master manifest. Set only on the READY transition. */
    @Column(length = 1024)
    private String hlsManifestUrl;

    /** Absolute URL of the generated poster JPEG. */
    @Column(length = 1024)
    private String posterUrl;

    /** Absolute URL of the sprite sheet the client uses for cover-frame selection. */
    @Column(length = 1024)
    private String thumbnailSpriteUrl;

    /** Cover frame chosen by the user, as a tile index into the sprite sheet. */
    private Integer thumbnailTileIndex;

    /** The adaptive ladder, as published by the transcoder. */
    @Convert(converter = RenditionListConverter.class)
    @Column(columnDefinition = "text")
    private List<RenditionInfo> renditions;

    private Integer durationSeconds;
    private Integer width;
    private Integer height;

    /** Size of the uploaded original in bytes, from {@code HeadObject}. */
    private Long sourceBytes;

    /** Content type of the uploaded original. */
    @Column(length = 128)
    private String sourceContentType;

    /** Why the video failed, as a {@code TranscodeFailureReason} name. */
    @Column(length = 64)
    private String failureReason;

    /** Human-readable detail. Log-bound, not user-facing. */
    @Column(length = 1024)
    private String failureMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        if (this.videoStatus == null) {
            this.videoStatus = VideoStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        this.isNew = false;
    }
}
