-- NOVA schema v1: accounts, assistant profile, voice sessions, transcripts, session events.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    time_zone     TEXT        NOT NULL DEFAULT 'Europe/Warsaw',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Configurable on-screen name, voice and personality (spec p.1: all three are configurable).
CREATE TABLE assistant_profiles (
    owner_id            UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    display_name        TEXT        NOT NULL,
    voice               TEXT        NOT NULL,
    instructions        TEXT        NOT NULL,
    turn_detection      TEXT        NOT NULL DEFAULT 'server_vad',
    vad_silence_ms      INT         NOT NULL DEFAULT 650,
    vad_eagerness       TEXT        NOT NULL DEFAULT 'auto',
    version             INT         NOT NULL DEFAULT 1,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT turn_detection_chk CHECK (turn_detection IN ('server_vad', 'semantic_vad')),
    CONSTRAINT vad_eagerness_chk CHECK (vad_eagerness IN ('low', 'medium', 'high', 'auto')),
    CONSTRAINT vad_silence_chk CHECK (vad_silence_ms BETWEEN 200 AND 3000)
);

CREATE TABLE sessions (
    id               UUID PRIMARY KEY,
    owner_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider         TEXT        NOT NULL,
    model            TEXT        NOT NULL,
    voice            TEXT        NOT NULL,
    provider_call_id TEXT,
    status           TEXT        NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    connected_at     TIMESTAMPTZ,
    ended_at         TIMESTAMPTZ,
    end_reason       TEXT,
    CONSTRAINT session_status_chk CHECK (status IN ('starting', 'active', 'ended', 'failed'))
);
CREATE INDEX sessions_owner_started_idx ON sessions (owner_id, started_at DESC);

-- Final transcripts of both sides. interrupted = the assistant was cut off by barge-in.
CREATE TABLE messages (
    id               UUID PRIMARY KEY,
    session_id       UUID        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    owner_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    seq              INT         NOT NULL,
    role             TEXT        NOT NULL,
    content          TEXT        NOT NULL,
    interrupted      BOOLEAN     NOT NULL DEFAULT FALSE,
    provider_item_id TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT message_role_chk CHECK (role IN ('user', 'assistant')),
    CONSTRAINT message_item_uq UNIQUE (session_id, provider_item_id),
    CONSTRAINT message_seq_uq UNIQUE (session_id, seq)
);

-- Session timeline (connection changes, latency samples, interruptions). Ordered by seq per session.
CREATE TABLE events (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    owner_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    seq         INT         NOT NULL,
    type        TEXT        NOT NULL,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT event_seq_uq UNIQUE (session_id, seq)
);

-- Spring Session JDBC (keeps the login across server redeploys).
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
