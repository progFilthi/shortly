-- Align token_hash with the entity.
--
-- V2 declared it CHAR(64), a fixed-width type that is arguably the more correct choice for a
-- hex SHA-256. The entity says VARCHAR(64), and Hibernate runs in validate mode, so the service
-- refused to start with:
--
--   Schema validation: wrong column type encountered in column [token_hash] in table
--   [refresh_tokens]; found [bpchar], but expecting [varchar(64)]
--
-- The two differ only in trailing-space semantics, which never applied to a hex string, so the
-- simpler type wins and validate mode is satisfied.

ALTER TABLE refresh_tokens
    ALTER COLUMN token_hash TYPE VARCHAR(64);

-- The unique index inherits the new type automatically; nothing to do there.
