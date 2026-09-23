-- File: 20260923123000_plugin_api_key_storage_form.sql
-- ============================================================================
-- Plugin-store API keys: make the at-rest storage form of key material a
-- database invariant
-- ============================================================================
--
-- Verified state at the time of writing (upstream dev):
--   * routes/api-keys.ts generates each key with crypto.getRandomValues,
--     stores only its SHA-256 digest in key_hash (utils/api-key.ts
--     hashApiKey) and only a 16-char display mask in key_prefix
--     (getKeyPrefix). The raw key is returned to the caller exactly once
--     and never written anywhere; validation hashes the presented key and
--     looks the digest up.
--   * Nothing in the schema enforced that form. key_hash and key_prefix
--     are bare TEXT columns, and every authenticated user holds direct
--     INSERT and UPDATE grants on their own rows ("Users can create own
--     API keys", "Users can update own API keys"), so any writer that
--     bypassed the edge function - a legacy client, a buggy import, a
--     future RPC - could rest RAW key material in either column with no
--     error and no trace, and any later DB read (service role, an RLS
--     hole, a definer function leak) would read live credentials.
--     boss_ai_connections pins its secret's format with a CHECK
--     constraint; the CI credential table had no equivalent.
--
-- This migration:
--   1. Defines repair_plugin_api_key_material() and runs it, so any row
--      that violates the form is brought back into it, idempotently:
--        - key_hash that is itself a raw boss_pk_ key is hashed in place
--          with pgcrypto - bit for bit what hashApiKey computes for the
--          presented key - so the owner's credential keeps working;
--        - key_hash that is neither a 64-char lowercase hex digest nor a
--          raw key can never be matched by a presented key (hashApiKey
--          always emits 64 lowercase hex chars), so the row is dead by
--          construction: it is revoked and the material scrubbed;
--        - key_prefix that is not exactly the 16-char mask may hold raw
--          material beyond the 8 display chars; it is replaced by a fixed
--          conforming placeholder. Validation reads key_hash only, so the
--          key itself keeps working - only its display changes.
--   2. Adds CHECK constraints that hold the form from here on, so raw key
--      material can never rest in either column again.
--   3. Corrects the column comments: the shipped display mask is 16 chars
--      (boss_pk_ + 8 random), not the 12 the original comments claimed.
-- ============================================================================

-- ============================================================================
-- SECTION 1: Repair function (idempotent; invoker security, so a caller
-- only ever repairs rows their own RLS grants let them write)
-- ============================================================================

CREATE OR REPLACE FUNCTION public.repair_plugin_api_key_material()
RETURNS TABLE (hashed bigint, revoked bigint, scrubbed bigint)
LANGUAGE plpgsql
VOLATILE
SET search_path TO ''
AS $$
DECLARE
    v_hashed   bigint := 0;
    v_revoked  bigint := 0;
    v_scrubbed bigint := 0;
BEGIN
    -- 1. Raw key material resting in key_hash: hash it in place. This is bit
    -- for bit what hashApiKey computes in the edge function for the presented
    -- key, so the credential survives the repair. A row whose digest already
    -- exists elsewhere (the same key also stored properly) is left for the
    -- second arm: one credential cannot be live twice, and the unique index on
    -- key_hash would reject the update anyway.
    UPDATE public.plugin_api_keys k
       SET key_hash = pg_catalog.encode(extensions.digest(k.key_hash, 'sha256'), 'hex')
     WHERE k.key_hash ~ '^boss_pk_[A-Za-z0-9]{32}$'
       AND NOT EXISTS (
            SELECT 1
              FROM public.plugin_api_keys p
             WHERE p.key_hash = pg_catalog.encode(extensions.digest(k.key_hash, 'sha256'), 'hex')
       );
    GET DIAGNOSTICS v_hashed = ROW_COUNT;

    -- 2. Whatever is still not a 64-char lowercase hex digest can never be
    -- matched by a presented key, so it is at best dead weight and at worst
    -- raw material at rest. Revoke the row (idempotently: keep an existing
    -- revoked_at) and replace the material with the digest of the row's own
    -- id: unique, conforming, and unreachable as a credential, because
    -- isValidApiKeyFormat only ever presents boss_pk_-shaped keys to
    -- hashApiKey, so no input can hash to a uuid-string digest.
    UPDATE public.plugin_api_keys k
       SET key_hash = pg_catalog.encode(extensions.digest(k.id::text, 'sha256'), 'hex'),
           revoked_at = COALESCE(k.revoked_at, now())
     WHERE k.key_hash !~ '^[0-9a-f]{64}$';
    GET DIAGNOSTICS v_revoked = ROW_COUNT;

    -- 3. A prefix that is not exactly the 16-char mask may be holding material
    -- beyond the 8 display chars. Replace it with a fixed placeholder that
    -- keeps the mask's shape; the key itself keeps validating.
    UPDATE public.plugin_api_keys
       SET key_prefix = 'boss_pk_scrubbed'
     WHERE key_prefix !~ '^boss_pk_[A-Za-z0-9]{8}$';
    GET DIAGNOSTICS v_scrubbed = ROW_COUNT;

    RAISE NOTICE 'plugin_api_keys storage-form repair: hashed % raw key(s) in place, revoked % non-digest row(s), scrubbed % unmasked prefix(es)',
        v_hashed, v_revoked, v_scrubbed;

    RETURN QUERY SELECT v_hashed, v_revoked, v_scrubbed;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.repair_plugin_api_key_material() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.repair_plugin_api_key_material() TO service_role;

COMMENT ON FUNCTION public.repair_plugin_api_key_material() IS 'Brings plugin_api_keys rows back into the enforced storage form: hashes raw key material found in key_hash in place (bit-identical to the edge function hashApiKey), revokes and scrubs rows whose key_hash can never match a presented key, and replaces unmasked key_prefix values with a fixed conforming placeholder. Idempotent: a second run repairs nothing.';

-- Run it once here; re-running the migration re-runs it harmlessly.
SELECT * FROM public.repair_plugin_api_key_material();

-- ============================================================================
-- SECTION 2: Enforce the form from here on
-- ============================================================================

ALTER TABLE public.plugin_api_keys
    DROP CONSTRAINT IF EXISTS plugin_api_keys_key_hash_digest_check;
ALTER TABLE public.plugin_api_keys
    ADD CONSTRAINT plugin_api_keys_key_hash_digest_check
    CHECK (key_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE public.plugin_api_keys
    DROP CONSTRAINT IF EXISTS plugin_api_keys_key_prefix_masked_check;
ALTER TABLE public.plugin_api_keys
    ADD CONSTRAINT plugin_api_keys_key_prefix_masked_check
    CHECK (key_prefix ~ '^boss_pk_[A-Za-z0-9]{8}$');

COMMENT ON CONSTRAINT plugin_api_keys_key_hash_digest_check ON public.plugin_api_keys IS 'key_hash must be a 64-char lowercase hex SHA-256 digest (utils/api-key.ts hashApiKey defines the canonical form), never raw key material.';
COMMENT ON CONSTRAINT plugin_api_keys_key_prefix_masked_check ON public.plugin_api_keys IS 'key_prefix must be exactly the 16-char display mask (boss_pk_ plus 8 random chars, utils/api-key.ts getKeyPrefix), never full key material.';

-- ============================================================================
-- SECTION 3: Comment corrections (the original comments claimed 12 chars;
-- the shipped mask is 16)
-- ============================================================================

COMMENT ON COLUMN public.plugin_api_keys.key_prefix IS 'boss_pk_ plus the first 8 random chars (16 total), display only. The masked form is enforced by plugin_api_keys_key_prefix_masked_check, so full key material cannot rest here.';
COMMENT ON COLUMN public.plugin_api_keys.key_hash IS 'SHA-256 digest of the full key, 64 lowercase hex chars. The digest form is enforced by plugin_api_keys_key_hash_digest_check, so raw key material cannot rest here.';
