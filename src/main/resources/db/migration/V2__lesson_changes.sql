-- Cancelling or moving a lesson never edits it away: the old row keeps its slot data with a
-- terminal status, a moved lesson is a NEW row pointing at the one it replaces, and every
-- change is appended to lesson_change. A tutor told "09:00 R1" can always see what that
-- lesson became and when, and whether it changed after the 16:00 lock.

-- 'rescheduled' = this lesson was moved; the replacement row has replaces_lesson_id = this id.
ALTER TABLE lesson DROP CONSTRAINT lesson_status_valid;
ALTER TABLE lesson ADD CONSTRAINT lesson_status_valid
    CHECK (status IN ('booked', 'cancelled', 'rescheduled', 'no_show'));

-- A lesson can be replaced at most once, so a reschedule chain never forks.
ALTER TABLE lesson ADD COLUMN replaces_lesson_id BIGINT UNIQUE REFERENCES lesson (id);

-- Decided once, at cancel time, from the 4-hour rule: TRUE = family charged, tutor paid.
-- Stored rather than recomputed so a later change of policy does not rewrite past invoices.
ALTER TABLE lesson ADD COLUMN late_cancel BOOLEAN;
ALTER TABLE lesson ADD CONSTRAINT lesson_late_cancel_iff_cancelled
    CHECK ((status = 'cancelled') = (late_cancel IS NOT NULL));

-- Only 'booked' and 'no_show' hold the slot: a cancel (even late) or a move releases it.
ALTER TABLE lesson DROP CONSTRAINT no_student_double_booking;
ALTER TABLE lesson DROP CONSTRAINT no_tutor_double_booking;
ALTER TABLE lesson DROP CONSTRAINT no_room_double_booking;
ALTER TABLE lesson ADD CONSTRAINT no_student_double_booking EXCLUDE USING gist (
    student_id WITH =, tstzrange(starts_at, ends_at) WITH &&
) WHERE (status IN ('booked', 'no_show') AND NOT legacy_conflict);
ALTER TABLE lesson ADD CONSTRAINT no_tutor_double_booking EXCLUDE USING gist (
    tutor_id WITH =, tstzrange(starts_at, ends_at) WITH &&
) WHERE (status IN ('booked', 'no_show') AND NOT legacy_conflict);
ALTER TABLE lesson ADD CONSTRAINT no_room_double_booking EXCLUDE USING gist (
    room_id WITH =, tstzrange(starts_at, ends_at) WITH &&
) WHERE (status IN ('booked', 'no_show') AND NOT legacy_conflict);

CREATE TABLE lesson_change (
    id         BIGSERIAL PRIMARY KEY,
    lesson_id  BIGINT NOT NULL REFERENCES lesson (id),
    change     TEXT NOT NULL CHECK (change IN ('booked', 'cancelled', 'rescheduled', 'no_show')),
    changed_at TIMESTAMPTZ NOT NULL,
    -- TRUE when made at or after 16:00 the day before the lesson, i.e. after the tutor got the
    -- locked schedule. These are the changes that must be shown, never applied silently.
    after_lock BOOLEAN NOT NULL,
    reason     TEXT
);
CREATE INDEX lesson_change_lesson ON lesson_change (lesson_id);

-- History is append-only: once a tutor may have seen a change, it cannot be edited or removed.
CREATE FUNCTION lesson_change_is_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lesson_change is append-only; record a new change instead';
END $$;
CREATE TRIGGER lesson_change_append_only
    BEFORE UPDATE OR DELETE ON lesson_change
    FOR EACH ROW EXECUTE FUNCTION lesson_change_is_append_only();
