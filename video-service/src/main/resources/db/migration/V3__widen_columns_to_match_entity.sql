-- Widen description beyond the varchar(255) Hibernate inferred from a bare String field.
--
-- A bare `private String description;` maps to varchar(255) and truncates silently on write.
-- The entity now declares length 2000, and Hibernate is in validate mode, so without this
-- migration the application refuses to start rather than quietly corrupting data. Better a
-- failed deploy than a silent one.

ALTER TABLE videos
    ALTER COLUMN description TYPE VARCHAR(2000);

-- Same for the columns the entity now declares explicitly, so validate mode agrees with
-- the entity rather than merely tolerating a shorter column.
ALTER TABLE videos
    ALTER COLUMN title        TYPE VARCHAR(200),
    ALTER COLUMN user_id      TYPE VARCHAR(128),
    ALTER COLUMN video_status TYPE VARCHAR(32),
    ALTER COLUMN s3_key       TYPE VARCHAR(512),
    ALTER COLUMN video_url    TYPE VARCHAR(1024);
