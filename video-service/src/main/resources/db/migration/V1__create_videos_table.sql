-- Baseline for the videos table.
--
-- Previously this schema did not exist in version control: video-service ran with
-- hibernate.ddl-auto=update, which meant Hibernate invented the table at boot from the entity.
-- That is fine until it isn't - it cannot express a rename, a narrowing, or a NOT NULL on an
-- existing column, and it silently mutates a production schema on startup. This migration
-- takes ownership of the schema; Hibernate is now set to validate.

CREATE TABLE IF NOT EXISTS videos (
    id              UUID         PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    description     VARCHAR(2000),
    video_url       VARCHAR(1024),
    s3_key          VARCHAR(512),
    user_id         VARCHAR(128) NOT NULL,
    video_status    VARCHAR(32)  NOT NULL,

    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

-- The only access pattern that is not a primary key lookup is a user's feed, newest first.
-- Without this the table is scanned and sorted on every profile view.
CREATE INDEX IF NOT EXISTS idx_videos_user_created_at
    ON videos (user_id, created_at DESC);

-- Supports finding videos stuck in PROCESSING for reconciliation and DLQ replay.
CREATE INDEX IF NOT EXISTS idx_videos_status
    ON videos (video_status);
