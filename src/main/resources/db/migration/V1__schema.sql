-- Bright Path scheduling schema.
--
-- Rules enforced HERE (cannot be bypassed by any code path, safe under concurrency):
--   * a student, a tutor and a room are each in at most one active lesson at any instant
--   * a lesson is 60 or 90 minutes
-- Rules enforced in application code (they need "now", a per-day count, or an override):
--   * closed on Monday, max 6 lessons per tutor per day, no booking in the past

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE tutor (
    id      TEXT PRIMARY KEY,          -- 'T1', as used in the spreadsheet
    name    TEXT NOT NULL,
    subject TEXT,
    phone   TEXT
);

CREATE TABLE room (
    id TEXT PRIMARY KEY                -- 'R1'..'R6'
);

CREATE TABLE student (
    id        BIGSERIAL PRIMARY KEY,
    -- The export only has names, so the name is the identity for now (see DECISIONS.md).
    full_name TEXT NOT NULL UNIQUE
);

CREATE TABLE lesson (
    id              BIGSERIAL PRIMARY KEY,
    external_ref    TEXT UNIQUE,       -- lesson_id from the spreadsheet export, NULL for new lessons
    student_id      BIGINT NOT NULL REFERENCES student (id),
    tutor_id        TEXT   NOT NULL REFERENCES tutor (id),
    room_id         TEXT   NOT NULL REFERENCES room (id),
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ NOT NULL,
    status          TEXT NOT NULL DEFAULT 'booked',
    cancelled_at    TIMESTAMPTZ,
    note            TEXT,
    -- Why a soft rule (Monday, 6/day) was knowingly broken. NULL = no rule broken.
    override_reason TEXT,
    -- TRUE only for imported rows that overlapped an earlier imported row.
    -- They are kept as history but do not hold a slot (excluded from the constraints below).
    legacy_conflict BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT lesson_duration_60_or_90
        CHECK (EXTRACT(EPOCH FROM (ends_at - starts_at)) / 60 IN (60, 90)),
    CONSTRAINT lesson_status_valid
        CHECK (status IN ('booked', 'cancelled', 'no_show')),
    CONSTRAINT lesson_cancelled_at_iff_cancelled
        CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL)),

    -- A cancelled lesson releases its slot (even a late cancel, per center rules);
    -- a no-show does not, so 'no_show' still holds the slot.
    CONSTRAINT no_student_double_booking EXCLUDE USING gist (
        student_id WITH =, tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status <> 'cancelled' AND NOT legacy_conflict),
    CONSTRAINT no_tutor_double_booking EXCLUDE USING gist (
        tutor_id WITH =, tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status <> 'cancelled' AND NOT legacy_conflict),
    CONSTRAINT no_room_double_booking EXCLUDE USING gist (
        room_id WITH =, tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status <> 'cancelled' AND NOT legacy_conflict)
);

CREATE INDEX lesson_tutor_day ON lesson (tutor_id, starts_at);

-- Every rule violation found while importing the spreadsheet, so the owner can review them.
CREATE TABLE import_issue (
    id           BIGSERIAL PRIMARY KEY,
    external_ref TEXT NOT NULL,
    rule         TEXT NOT NULL,        -- e.g. 'no_room_double_booking', 'closed_on_monday'
    detail       TEXT NOT NULL
);

INSERT INTO room (id) VALUES ('R1'), ('R2'), ('R3'), ('R4'), ('R5'), ('R6');
