-- Sign-in hardening and refresh token storage.
--
-- Both live here rather than in V1 because V1 is the Flyway baseline: on an environment that
-- predates Flyway, V1 is recorded as already applied and never runs, so anything additive in it
-- would silently never reach that database.

-- Per-account failed-attempt counter, reset on any success.
-- Stored on the account rather than in an IP-keyed rate limiter, because a brute-force run
-- against one account comes from many addresses and a per-IP limiter misses it entirely.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS failed_login_attempts INTEGER NOT NULL DEFAULT 0;

-- While set and in the future, every sign-in is refused regardless of the password.
-- Cleared lazily on the next attempt rather than by a scheduled job, so there is nothing to
-- keep running and nothing to fall over.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;

-- Refresh tokens, for rotation and reuse detection.
--
-- Stored as a SHA-256 hash, never in the clear: a database leak must not hand an attacker
-- working credentials. `family_id` groups every token descended from one sign-in, which is
-- what makes a stolen token detectable - knowing "this token was already used" identifies a
-- token but not the rest of the session to revoke.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    family_id   UUID        NOT NULL,
    issued_at   TIMESTAMP   NOT NULL,
    expires_at  TIMESTAMP   NOT NULL,
    consumed_at TIMESTAMP,
    revoked_at  TIMESTAMP,

    -- Best-effort client context for a "your active sessions" screen and abuse triage.
    user_agent  VARCHAR(255),
    ip_address  VARCHAR(45)
);

-- The lookup path for every refresh. Must be unique: two rows sharing a hash would make
-- rotation ambiguous.
CREATE UNIQUE INDEX IF NOT EXISTS uq_refresh_tokens_hash ON refresh_tokens (token_hash);

-- Reuse detection revokes a whole family in one UPDATE.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family ON refresh_tokens (family_id);

-- Backs "log out everywhere" and the active-session count.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens (user_id);

-- Backs the purge of expired rows. Without it, housekeeping would have to scan the table.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- No foreign key to users: auth rows outlive a deleted account by design, so logout or reuse
-- detection can still revoke them. Orphans are reaped by the expiry purge.
