-- Step 3 (M4): seven-day experiment, activity budget (reservations + expenses), daily reviews.

CREATE TABLE experiments (
    id                     UUID PRIMARY KEY,
    owner_id               UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    goal                   TEXT        NOT NULL,
    start_date             DATE        NOT NULL,
    days                   INT         NOT NULL DEFAULT 7,
    work_start             TEXT        NOT NULL,
    work_end               TEXT        NOT NULL,
    exclusions             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    budget_grosze          BIGINT      NOT NULL,
    api_limit_grosze       BIGINT      NOT NULL,
    day_start              TEXT        NOT NULL DEFAULT '04:00',
    baseline               JSONB       NOT NULL DEFAULT '{}'::jsonb,
    rules_version          INT         NOT NULL DEFAULT 1,
    status                 TEXT        NOT NULL DEFAULT 'draft',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at           TIMESTAMPTZ,
    ended_at               TIMESTAMPTZ,
    CONSTRAINT experiment_status_chk CHECK (status IN ('draft', 'active', 'paused', 'completed', 'ended_early')),
    CONSTRAINT experiment_budget_chk CHECK (budget_grosze >= 0 AND api_limit_grosze >= 0),
    CONSTRAINT experiment_days_chk CHECK (days BETWEEN 1 AND 31)
);
-- at most one running experiment per owner
CREATE UNIQUE INDEX experiments_one_running_idx ON experiments (owner_id) WHERE status IN ('draft', 'active', 'paused');

-- Activation, pause, resume, early end: decision and date (spec p.6).
CREATE TABLE experiment_decisions (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id UUID        NOT NULL REFERENCES experiments (id) ON DELETE CASCADE,
    decision      TEXT        NOT NULL,
    note          TEXT,
    rules_version INT         NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id            UUID PRIMARY KEY,
    owner_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    experiment_id UUID        NOT NULL REFERENCES experiments (id) ON DELETE CASCADE,
    task_id       UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    amount_grosze BIGINT      NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'open',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ,
    CONSTRAINT reservation_status_chk CHECK (status IN ('open', 'converted', 'released')),
    CONSTRAINT reservation_amount_chk CHECK (amount_grosze > 0)
);
CREATE UNIQUE INDEX reservations_one_open_per_task_idx ON reservations (task_id) WHERE status = 'open';

CREATE TABLE expenses (
    id              UUID PRIMARY KEY,
    owner_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    experiment_id   UUID        NOT NULL REFERENCES experiments (id) ON DELETE CASCADE,
    task_id         UUID        REFERENCES tasks (id) ON DELETE SET NULL,
    reservation_id  UUID        REFERENCES reservations (id) ON DELETE SET NULL,
    amount_grosze   BIGINT      NOT NULL,
    currency        TEXT        NOT NULL DEFAULT 'PLN',
    description     TEXT        NOT NULL,
    expense_date    DATE        NOT NULL,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT expense_amount_chk CHECK (amount_grosze > 0),
    CONSTRAINT expense_currency_chk CHECK (currency = 'PLN'),
    CONSTRAINT expense_idem_uq UNIQUE (owner_id, idempotency_key)
);

-- One editable review per day.
CREATE TABLE daily_reviews (
    id            UUID PRIMARY KEY,
    owner_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    experiment_id UUID        NOT NULL REFERENCES experiments (id) ON DELETE CASCADE,
    review_date   DATE        NOT NULL,
    rating        SMALLINT    NOT NULL,
    comment       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT review_rating_chk CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT review_day_uq UNIQUE (experiment_id, review_date)
);

ALTER TABLE tasks ADD COLUMN actual_min INT;

ALTER TABLE approvals DROP CONSTRAINT approval_kind_chk;
ALTER TABLE approvals ADD CONSTRAINT approval_kind_chk CHECK (kind IN ('plan_accept', 'plan_change', 'activity'));
