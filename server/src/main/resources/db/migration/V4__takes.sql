-- Step 4 (M5): recording mode. A take is one camera shot; each event carries its offset from the take's zero
-- point so it can be found on the footage (spec p.7).

CREATE TABLE takes (
    id          UUID PRIMARY KEY,
    owner_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    session_id  UUID        REFERENCES sessions (id) ON DELETE SET NULL,
    take_date   DATE        NOT NULL,
    label       TEXT,
    number      INT         NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'recording',
    started_at  TIMESTAMPTZ NOT NULL,
    ended_at    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT take_status_chk CHECK (status IN ('recording', 'paused', 'closed')),
    CONSTRAINT take_number_uq UNIQUE (owner_id, take_date, number)
);
CREATE UNIQUE INDEX takes_one_open_idx ON takes (owner_id) WHERE status IN ('recording', 'paused');

CREATE TABLE take_events (
    id              UUID PRIMARY KEY,
    owner_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    take_id         UUID        NOT NULL REFERENCES takes (id) ON DELETE CASCADE,
    seq             INT         NOT NULL,
    type            TEXT        NOT NULL,
    speaker         TEXT,
    text            TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL,
    offset_ms       BIGINT      NOT NULL,
    private         BOOLEAN     NOT NULL DEFAULT FALSE,
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    client_event_id TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT take_event_type_chk CHECK (type IN ('sync', 'utterance', 'interruption', 'decision', 'tool', 'marker', 'gap',
                                                   'pause', 'resume', 'connection_lost', 'connection_restored', 'privacy', 'stop')),
    CONSTRAINT take_event_seq_uq UNIQUE (take_id, seq),
    CONSTRAINT take_event_client_uq UNIQUE (take_id, client_event_id)
);
CREATE INDEX take_events_take_seq_idx ON take_events (take_id, seq);
