-- Source of truth for rate history. Review/adjust before running —
-- see backend/AGENTS.md section 8 (Open decisions) before extending this.
-- Deliberately simple: one row per fetch, pairs as columns rather than a
-- generic key-value table, since the scope is fixed to USD/VND and EUR/VND.

create table rate_history (
    id bigserial primary key,

    -- rates as returned by the external API for this fetch
    usd_to_vnd numeric(18, 4) not null,
    eur_to_vnd numeric(18, 4) not null,

    -- `fetched_at` should be the external API's own `time_last_update_utc`
    -- (when the SOURCE data was last updated), not the moment our scheduler
    -- happened to call it. This distinction matters for US-04's "no
    -- duplicate rows on retry-then-success" requirement: if a fetch is
    -- retried and eventually succeeds, it's still reporting the SAME
    -- underlying update from the source, so it should not create a second
    -- history row for data that hasn't actually changed.
    fetched_at timestamptz not null,

    -- the external API's `time_next_update_utc` at the time of this fetch —
    -- used by the scheduler (US-02) to decide when to call again
    next_update_at timestamptz,

    created_at timestamptz default now()
);

-- Enforces the "no duplicate rows for the same source update" rule at the
-- database level, rather than relying only on application logic to get it
-- right. If the scheduler/client somehow calls this twice for the same
-- `fetched_at`, the second insert should be rejected (or upserted — see
-- note below), not silently create a duplicate history row.
alter table rate_history
    add constraint uq_rate_history_fetched_at unique (fetched_at);

create index idx_rate_history_fetched_at on rate_history (fetched_at desc);

-- Open question for you (see AGENTS.md section 8): when a fetch resolves
-- to a `fetched_at` that already exists (e.g. the scheduler woke up before
-- the source actually updated, or a retry raced a scheduled call), should
-- the insert:
--   (a) fail loudly (current behavior, via the unique constraint) — forces
--       the application layer to explicitly check-before-insert or catch
--       the constraint violation, or
--   (b) upsert silently (ON CONFLICT (fetched_at) DO NOTHING, or DO UPDATE)
-- This is intentionally left as (a) for now so you hit the conflict once
-- in testing and can reason about which behavior is actually correct for
-- US-04, rather than it being decided for you.