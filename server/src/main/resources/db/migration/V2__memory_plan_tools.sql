-- Step 2 (M2 + M3): memory, local tasks, daily plan with versions, manual busy blocks,
-- tool executions, approvals and a per-owner event log for SSE.

CREATE TABLE memories (
    id                UUID PRIMARY KEY,
    owner_id          UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind              TEXT        NOT NULL,
    content           TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    importance        SMALLINT    NOT NULL DEFAULT 3,
    source_session_id UUID        REFERENCES sessions (id) ON DELETE SET NULL,
    source_note       TEXT,
    version           INT         NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    search            TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    CONSTRAINT memory_kind_chk CHECK (kind IN ('fact', 'inference', 'episode')),
    CONSTRAINT memory_status_chk CHECK (status IN ('confirmed', 'pending', 'rejected', 'deleted')),
    CONSTRAINT memory_importance_chk CHECK (importance BETWEEN 1 AND 5)
);
CREATE INDEX memories_owner_status_idx ON memories (owner_id, status);
CREATE INDEX memories_search_idx ON memories USING GIN (search);

-- Audit keeps the operation and id, never the deleted text (spec p.4).
CREATE TABLE memory_audit (
    id          BIGSERIAL PRIMARY KEY,
    owner_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    memory_id   UUID        NOT NULL,
    operation   TEXT        NOT NULL,
    version     INT         NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Manually entered busy time (first demo source of availability, spec p.2).
CREATE TABLE busy_blocks (
    id         UUID PRIMARY KEY,
    owner_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,
    label      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT busy_range_chk CHECK (ends_at > starts_at)
);
CREATE INDEX busy_blocks_owner_start_idx ON busy_blocks (owner_id, starts_at);

CREATE TABLE daily_plans (
    id         UUID PRIMARY KEY,
    owner_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_date  DATE        NOT NULL,
    version    INT         NOT NULL DEFAULT 1,
    status     TEXT        NOT NULL DEFAULT 'draft',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT plan_status_chk CHECK (status IN ('draft', 'approved')),
    CONSTRAINT plan_owner_date_uq UNIQUE (owner_id, plan_date)
);

CREATE TABLE approvals (
    id            UUID PRIMARY KEY,
    owner_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    session_id    UUID        REFERENCES sessions (id) ON DELETE SET NULL,
    kind          TEXT        NOT NULL,
    plan_id       UUID        NOT NULL REFERENCES daily_plans (id) ON DELETE CASCADE,
    plan_version  INT         NOT NULL,
    args          JSONB       NOT NULL,
    summary       TEXT        NOT NULL,
    conflict      TEXT,
    status        TEXT        NOT NULL DEFAULT 'pending',
    expires_at    TIMESTAMPTZ NOT NULL,
    decided_at    TIMESTAMPTZ,
    decision_key  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT approval_kind_chk CHECK (kind IN ('plan_accept', 'plan_change')),
    CONSTRAINT approval_status_chk CHECK (status IN ('pending', 'approved', 'rejected', 'expired', 'superseded'))
);
CREATE INDEX approvals_owner_status_idx ON approvals (owner_id, status);

CREATE TABLE tasks (
    id              UUID PRIMARY KEY,
    owner_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_id         UUID        NOT NULL REFERENCES daily_plans (id) ON DELETE CASCADE,
    proposal_id     UUID        REFERENCES approvals (id) ON DELETE SET NULL,
    title           TEXT        NOT NULL,
    start_at        TIMESTAMPTZ,
    duration_min    INT         NOT NULL DEFAULT 30,
    cost_grosze     BIGINT      NOT NULL DEFAULT 0,
    reason          TEXT,
    status          TEXT        NOT NULL,
    refusal_reason  TEXT,
    source          TEXT        NOT NULL,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT task_status_chk CHECK (status IN ('proposed', 'planned', 'done', 'skipped', 'refused', 'cancelled')),
    CONSTRAINT task_source_chk CHECK (source IN ('user', 'assistant')),
    CONSTRAINT task_duration_chk CHECK (duration_min BETWEEN 1 AND 1440),
    CONSTRAINT task_cost_chk CHECK (cost_grosze >= 0),
    CONSTRAINT task_idem_uq UNIQUE (owner_id, idempotency_key)
);
CREATE INDEX tasks_plan_idx ON tasks (plan_id);

-- Every plan change: new version + diff (spec p.6).
CREATE TABLE plan_changes (
    id           BIGSERIAL PRIMARY KEY,
    owner_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_id      UUID        NOT NULL REFERENCES daily_plans (id) ON DELETE CASCADE,
    from_version INT         NOT NULL,
    to_version   INT         NOT NULL,
    cause        TEXT        NOT NULL,
    diff         JSONB       NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per model function call; (session_id, call_id) is the retry key.
CREATE TABLE tool_executions (
    id          UUID PRIMARY KEY,
    owner_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    session_id  UUID        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    call_id     TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    args        JSONB       NOT NULL,
    status      TEXT        NOT NULL,
    result      JSONB       NOT NULL,
    approval_id UUID        REFERENCES approvals (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tool_status_chk CHECK (status IN ('ok', 'error', 'pending_approval', 'skipped')),
    CONSTRAINT tool_call_uq UNIQUE (session_id, call_id)
);

-- Ordered change feed per owner. Clients resume SSE from the last seq they saw.
CREATE TABLE owner_events (
    seq         BIGSERIAL PRIMARY KEY,
    owner_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        TEXT        NOT NULL,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX owner_events_owner_seq_idx ON owner_events (owner_id, seq);
