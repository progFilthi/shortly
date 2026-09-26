-- Fields the transcoder populates, plus the upload-verification and failure details.
--
-- Every column here is nullable and additive, so this is safe to apply to a table that
-- already has rows: existing videos keep their current status and simply have no ladder yet.

ALTER TABLE videos
    -- Where the adaptive ladder actually lives. video_url is kept and pointed at the same
    -- manifest, because the iOS client reads videoUrl today.
    ADD COLUMN IF NOT EXISTS hls_manifest_url        VARCHAR(1024),

    -- Generated cover art. poster_url is the transcoder's pick; thumbnail_tile_index is
    -- whatever the user chose from the sprite sheet, and null means "use the poster".
    ADD COLUMN IF NOT EXISTS poster_url              VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS thumbnail_sprite_url    VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS thumbnail_tile_index    INTEGER,

    -- Output geometry after the canonical 9:16 transform, not the source geometry.
    ADD COLUMN IF NOT EXISTS duration_seconds        INTEGER,
    ADD COLUMN IF NOT EXISTS width                   INTEGER,
    ADD COLUMN IF NOT EXISTS height                  INTEGER,

    -- Authoritative upload facts, from HeadObject rather than from the client.
    ADD COLUMN IF NOT EXISTS source_bytes            BIGINT,
    ADD COLUMN IF NOT EXISTS source_content_type     VARCHAR(128),

    -- The adaptive ladder as JSON. Not a child table: it is only ever read as a whole.
    ADD COLUMN IF NOT EXISTS renditions              TEXT,

    -- Machine-readable failure cause, plus a human-readable detail that is log-bound.
    ADD COLUMN IF NOT EXISTS failure_reason          VARCHAR(64),
    ADD COLUMN IF NOT EXISTS failure_message         VARCHAR(1024);

-- Constraints that must hold regardless of which code path wrote the row.
ALTER TABLE videos
    ADD CONSTRAINT chk_videos_duration_non_negative
        CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    ADD CONSTRAINT chk_videos_source_bytes_non_negative
        CHECK (source_bytes IS NULL OR source_bytes >= 0),
    ADD CONSTRAINT chk_videos_tile_index_non_negative
        CHECK (thumbnail_tile_index IS NULL OR thumbnail_tile_index >= 0);
