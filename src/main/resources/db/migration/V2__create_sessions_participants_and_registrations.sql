CREATE TABLE participants (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL
);

CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    coach_id UUID NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    capacity INTEGER NOT NULL,
    location TEXT NOT NULL,
    CONSTRAINT fk_sessions_coach FOREIGN KEY (coach_id) REFERENCES coaches (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sessions_positive_capacity CHECK (capacity > 0),
    CONSTRAINT ck_sessions_valid_interval CHECK (start_time < end_time)
);

CREATE TABLE registrations (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    CONSTRAINT fk_registrations_session FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_registrations_participant FOREIGN KEY (participant_id) REFERENCES participants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_registrations_session_participant UNIQUE (session_id, participant_id)
);

CREATE INDEX ix_sessions_coach_start_id ON sessions (coach_id, start_time, id);
CREATE INDEX ix_sessions_start_id ON sessions (start_time, id);
