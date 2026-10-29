# Bright Path scheduling

A small internal API for the Bright Path tutoring center. It books, cancels, reschedules and records no-shows for lessons. A student, tutor or room can never be double-booked, and every change made after a tutor was told is kept and shown, never overwritten.

Why it is built this way, what was left out and what is still weak: see **[DECISIONS.md](DECISIONS.md)**.

## Requirements
- Java 21
- Docker (for Postgres 16 and for the tests)

Maven itself is not needed; use the wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Run
```bash
docker compose up -d          # Postgres 16 on localhost:5433
./mvnw spring-boot:run        # API on http://localhost:8081
```
On the first start, Flyway creates the schema and the seed importer loads `data/tutors.csv` and `data/seed.csv` (the center's real spreadsheet export). It prints every rule violation it found:
```
SeedImporter : Imported 34 lessons from data\seed.csv
SeedImporter : Seed import finished with 6 issue(s):
  L008 no_student_double_booking: overlaps L007
  L010 no_room_double_booking: overlaps L009
  L010 no_tutor_double_booking: overlaps L009
  L027 max_6_lessons_per_tutor_per_day: T1 already has 6 on 2026-03-06
  L032 closed_on_monday: lesson on 2026-03-09
  L034 no_tutor_double_booking: overlaps L033
```
The import only runs when the `lesson` table is empty, so restarting is safe.

To start again from a clean seed:
```bash
docker compose exec db psql -U brightpath -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```

### Configuration
| Variable | Default | Meaning |
|---|---|---|
| `APP_NOW` | `2026-03-05T14:00:00+07:00` | Simulated "now" (Thursday of the seed week). Set it to an empty string to use the real clock. |
| `PORT` | `8081` | HTTP port (8080 is often taken on dev machines) |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5433/brightpath`, `brightpath` / `brightpath` | Database |

## Test
```bash
./mvnw test
```
The tests start their own throwaway Postgres (Testcontainers), import the real seed into it and leave the dev database untouched. Docker must be running.
```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

## UI
Open **http://localhost:8081/**: a single page for Mai and tutors (Vietnamese labels).
- Pick a tutor and a day to see every lesson. Cancelled and moved lessons stay visible. Lessons changed after the 16:00 lock are highlighted, and the revision number is shown.
- Cancel, reschedule or mark a no-show from each lesson; each opens a dialog that explains the consequence (for example "within 4h: still charged") and keeps any rule error inside the dialog. Book from "Đặt buổi học" in the sidebar. If a soft rule (Monday, more than 6 per day) blocks a booking, a reason field appears.
- `http://localhost:8081/?tutor=T1&date=2026-03-05` opens one tutor's day directly. That is the link to send a tutor instead of a chain of messages.

It is plain HTML, CSS and JS in `src/main/resources/static/index.html`, with lucide icons inlined as SVG, no build step and no external requests. Changes to the file need an app restart, because `spring-boot:run` serves the copy in `target/classes`. It was built after the 2.5h timebox (see DECISIONS.md).

## Try it
Lesson ids match the spreadsheet: lesson `16` is `L016`. Student ids follow the order of first appearance in `data/seed.csv` (1 = Le Minh Chau ... 6 = Bui An Nhien). All output below is real, from running these commands in order on a fresh seed with now = Thu 2026-03-05 14:00.

**A student cannot be in two places.** Le Minh Chau already has L019 on Fri 09:00 with T2:
```bash
curl -s -X POST localhost:8081/lessons -H 'Content-Type: application/json' \
  -d '{"studentId":1,"tutorId":"T3","roomId":"R4","startsAt":"2026-03-06T09:30","durationMin":60}'
```
```json
{"detail":"Clashes with an existing lesson (no_student_double_booking)","instance":"/lessons","status":409,"title":"Request rejected by center rules","rule":"no_student_double_booking","details":[{"rule":"no_student_double_booking","lessonId":19,"externalRef":"L019"}]}
```

**Soft rules need a reason.** A Monday booking without `overrideReason`:
```json
{"detail":"Breaks center rules [closed_on_monday]; resend with overrideReason to book anyway","instance":"/lessons","status":422,"title":"Request rejected by center rules","rule":"closed_on_monday","details":["closed_on_monday"]}
```
With one, it is booked and the reason is stored:
```bash
curl -s -X POST localhost:8081/lessons -H 'Content-Type: application/json' \
  -d '{"studentId":4,"tutorId":"T2","roomId":"R4","startsAt":"2026-03-09T09:00","durationMin":60,"overrideReason":"Family can only come Monday; owner approved"}'
```
```json
{"id":35,"externalRef":null,"studentId":4,"tutorId":"T2","roomId":"R4","startsAt":"2026-03-09T09:00:00+07:00","endsAt":"2026-03-09T10:00:00+07:00","status":"booked","lateCancel":null,"replacesLessonId":null,"overrideReason":"Family can only come Monday; owner approved"}
```

**Late cancel.** L016 is today at 14:30, 30 minutes from now. It is charged, its slot is freed, and it is flagged as a change after the lock:
```bash
curl -s -X POST localhost:8081/lessons/16/cancel -H 'Content-Type: application/json' -d '{"reason":"family cancelled"}'
```
```json
{"id":16,"externalRef":"L016","studentId":3,"tutorId":"T1","roomId":"R1","startsAt":"2026-03-05T14:30:00+07:00","endsAt":"2026-03-05T16:00:00+07:00","status":"cancelled","lateCancel":true,"replacesLessonId":null,"overrideReason":null}
```
Cancelling it again:
```json
{"detail":"Lesson 16 is already cancelled","instance":"/lessons/16/cancel","status":409,"title":"Request rejected by center rules","rule":"lesson_not_booked"}
```

**Reschedule.** This creates a new lesson pointing at the old one. The old one stays on the schedule as `rescheduled`:
```bash
curl -s -X POST localhost:8081/lessons/28/reschedule -H 'Content-Type: application/json' \
  -d '{"startsAt":"2026-03-07T13:00","reason":"exam moved"}'
```
```json
{"id":36,"externalRef":null,"studentId":2,"tutorId":"T3","roomId":"R3","startsAt":"2026-03-07T13:00:00+07:00","endsAt":"2026-03-07T14:30:00+07:00","status":"booked","lateCancel":null,"replacesLessonId":28,"overrideReason":null}
```

**The tutor's day: the single source of truth**
```bash
curl -s "localhost:8081/tutors/T1/schedule?date=2026-03-05"
```
```json
{
  "tutorId": "T1",
  "date": "2026-03-05",
  "lockedAt": "2026-03-04T16:00:00+07:00",
  "locked": true,
  "revision": 1,
  "changedAfterLock": [16],
  "lessons": [
    { "lessonId": 13, "externalRef": "L013", "studentName": "Bui An Nhien", "roomId": "R1",
      "startsAt": "2026-03-05T09:00:00+07:00", "endsAt": "2026-03-05T10:00:00+07:00",
      "status": "booked", "changes": [] },
    { "lessonId": 16, "externalRef": "L016", "studentName": "Nguyen Thi Ha", "roomId": "R1",
      "startsAt": "2026-03-05T14:30:00+07:00", "endsAt": "2026-03-05T16:00:00+07:00",
      "status": "cancelled", "lateCancel": true,
      "changes": [ { "change": "cancelled", "changedAt": "2026-03-05T14:00:00+07:00",
                     "afterLock": true, "reason": "family cancelled" } ] }
  ]
}
```
(Null fields are trimmed here for reading.)

## Layout
```
data/                         spreadsheet export used as seed
src/main/resources/db/migration/
  V1__schema.sql              tables + no-double-booking exclusion constraints
  V2__lesson_changes.sql      reschedule/cancel columns, append-only lesson_change
src/main/java/.../lesson/     booking, cancel, reschedule, no-show (LessonService), rules (CenterRules)
src/main/java/.../schedule/   GET /tutors/{id}/schedule
src/main/java/.../seed/       CSV import that keeps and reports rule violations
src/main/java/.../ClockConfig simulated "now"
```
