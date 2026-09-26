-- Baseline for the users table.
--
-- This schema predates Flyway: it was created by hibernate.ddl-auto=update, so an existing
-- environment already has a populated `users` table and no schema history. `baseline-on-migrate`
-- with `baseline-version: 1` records V1 as already applied there, so V1 runs ONLY on a fresh
-- database.
--
-- Consequence worth stating plainly: because V1 is the baseline, it must contain nothing that an
-- existing environment needs. Anything additive has to live in V2 or later, or it will silently
-- never be applied to a baselined database. That is exactly the mistake this file's first
-- version made by declaring the sign-in lockout columns here.

CREATE TABLE IF NOT EXISTS users (
    id                  VARCHAR(36)  PRIMARY KEY,
    username            VARCHAR(64)  NOT NULL,
    email               VARCHAR(320) NOT NULL,
    password            VARCHAR(100) NOT NULL,
    profile_picture_url VARCHAR(1024),
    bio                 VARCHAR(500),
    created_at          TIMESTAMP    NOT NULL
);

-- Login resolves by username OR email on every attempt. Without these the unique-constraint
-- check degrades to a full scan under contention.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username ON users (username);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email ON users (email);

-- Case-insensitive lookup, since identifiers are matched that way.
CREATE INDEX IF NOT EXISTS idx_users_username_lower ON users (LOWER(username));
CREATE INDEX IF NOT EXISTS idx_users_email_lower ON users (LOWER(email));
