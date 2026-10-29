# Bright Path scheduling

A small internal tool for the Bright Path tutoring centre. It books, cancels, reschedules and records no-shows for lessons, and shows today's schedule for the whole centre on one screen.

- A student, tutor or room can never be double-booked. The database enforces this.
- Every change made after a tutor was told is kept and shown, never overwritten.

Why it is built this way, what was left out and what is still weak: see **[DECISIONS.md](DECISIONS.md)**.

## Requirements
- Java 21
- Docker (for Postgres 16 and for the tests)

Maven itself is not needed; use the wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Run
```bash
docker compose up -d          # Postgres 16 on localhost:5433
./mvnw spring-boot:run        # app on http://localhost:8081
```
On the first start, Flyway creates the schema and the seed importer loads `data/tutors.csv` and `data/seed.csv` (the centre's real spreadsheet export). It prints every rule violation it found:
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
The import only runs when the `lesson` table is empty, so restarting is safe. To start again from a clean seed:
```bash
docker compose exec db psql -U brightpath -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```
Then restart the app.

| Variable | Default | Meaning |
|---|---|---|
| `APP_NOW` | `2026-03-05T14:00:00+07:00` | Simulated "now": Thursday of the seed week. An empty value uses the real clock. |
| `PORT` | `8081` | HTTP port (8080 is often taken on dev machines) |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5433/brightpath`, `brightpath` / `brightpath` | Database |

## Test
```bash
./mvnw test
```
The tests start their own throwaway Postgres (Testcontainers), import the real seed into it and leave the dev database untouched. Docker must be running.
```
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
```

## UI
Open **http://localhost:8081/**. It is a single page with Vietnamese labels, for Mai, the owner and tutors.
- **Hôm nay** (home): the whole centre's day as rooms R1–R6 × hours. The hour height adapts so a full day fits a laptop screen without scrolling.
  - The colour bar on each block shows its status: green booked, grey cancelled or moved, amber no-show.
  - An amber dot marks a change after the 16:00 lock.
  - A red line marks the simulated now.
- **Lịch gia sư**: one tutor's week. `?tutor=T1&date=2026-03-05` opens it directly. That is the link to send a tutor instead of a chain of messages.
- **Click any block** for its details and change history.
  - An upcoming lesson can be moved or cancelled.
  - A started lesson can only be marked as a no-show.
  - Each action explains its consequence first (for example "within 4h: still charged").
- **Đặt buổi học**: the booking form.

It is plain HTML, CSS and JS in `src/main/resources/static/index.html`, with lucide icons inlined as SVG, no build step and no external requests. Changes to the file need an app restart, because `spring-boot:run` serves the copy in `target/classes`. The UI was built after the 2.5h timebox (see DECISIONS.md).

## API, with real output
Lesson ids match the spreadsheet: lesson `16` is `L016`. Student ids follow the order of first appearance in `data/seed.csv` (1 = Le Minh Chau … 6 = Bui An Nhien). The output below is real. It comes from running these commands in order on a fresh seed with now = Thu 2026-03-05 14:00.

| Method and path | Does |
|---|---|
| `POST /lessons` | Book a lesson: `{studentId, tutorId, roomId, startsAt, durationMin}` |
| `POST /lessons/{id}/cancel` | Cancel: `{reason?}`. Applies the 4h rule and frees the slot |
| `POST /lessons/{id}/no-show` | Mark a no-show: keeps the slot and the charge |
| `POST /lessons/{id}/reschedule` | Move: `{startsAt, durationMin?, tutorId?, roomId?, reason?}`. Returns the new lesson |
| `GET /schedule?date=` | The whole centre's day |
| `GET /tutors/{id}/schedule?date=` | One tutor's day |

**A student cannot be in two places.** Le Minh Chau already has L019 on Fri 09:00:
```bash
curl -s -X POST localhost:8081/lessons -H 'Content-Type: application/json' \
  -d '{"studentId":1,"tutorId":"T3","roomId":"R4","startsAt":"2026-03-06T09:30","durationMin":60}'
```
```json
{"detail":"Clashes with an existing lesson (no_student_double_booking)","instance":"/lessons","status":409,"title":"Request rejected by center rules","rule":"no_student_double_booking","details":[{"rule":"no_student_double_booking","lessonId":19,"externalRef":"L019"}]}
```

**Monday and the 6-per-day limit are hard rules.** A Monday booking, and a booking for T1 on Friday:
```json
{"detail":"The centre is closed on Monday, when the rooms are cleaned","instance":"/lessons","status":422,"title":"Request rejected by center rules","rule":"closed_on_monday"}
{"detail":"Tutor T1 already has 7 lessons on 2026-03-06","instance":"/lessons","status":422,"title":"Request rejected by center rules","rule":"max_6_lessons_per_tutor_per_day"}
```
(T1 already has 7 because the export really ran 7. Imported history is kept as it was.)

**Late cancel.** L016 is today at 14:30, 30 minutes from now. It is charged and its slot is freed:
```bash
curl -s -X POST localhost:8081/lessons/16/cancel -H 'Content-Type: application/json' -d '{"reason":"family cancelled"}'
```
```json
{"id":16,"externalRef":"L016","studentId":3,"tutorId":"T1","roomId":"R1","startsAt":"2026-03-05T14:30:00+07:00","endsAt":"2026-03-05T16:00:00+07:00","status":"cancelled","lateCancel":true,"replacesLessonId":null}
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
{"id":35,"externalRef":null,"studentId":2,"tutorId":"T3","roomId":"R3","startsAt":"2026-03-07T13:00:00+07:00","endsAt":"2026-03-07T14:30:00+07:00","status":"booked","lateCancel":null,"replacesLessonId":28}
```

**Today for the whole centre.** L016 and L017 changed after Wednesday's 16:00 lock:
```bash
curl -s "localhost:8081/schedule?date=2026-03-05"
```
```json
{
  "tutorId": null, "date": "2026-03-05", "lockedAt": "2026-03-04T16:00:00+07:00", "locked": true,
  "revision": 2, "changedAfterLock": [16, 17],
  "lessons": [
    { "lessonId": 13, "externalRef": "L013", "studentName": "Bui An Nhien", "tutorName": "Ngoc Anh", "roomId": "R1",
      "startsAt": "2026-03-05T09:00:00+07:00", "status": "booked", "changes": [] },
    { "lessonId": 16, "externalRef": "L016", "studentName": "Nguyen Thi Ha", "tutorName": "Ngoc Anh", "roomId": "R1",
      "startsAt": "2026-03-05T14:30:00+07:00", "status": "cancelled", "lateCancel": true,
      "changes": [ { "change": "cancelled", "changedAt": "2026-03-05T14:00:00+07:00", "afterLock": true, "reason": "family cancelled" } ] },
    { "lessonId": 17, "externalRef": "L017", "studentName": "Do Van Kien", "tutorName": "Le Thu", "roomId": "R3",
      "startsAt": "2026-03-05T16:00:00+07:00", "status": "cancelled", "lateCancel": true,
      "changes": [ { "change": "cancelled", "changedAt": "2026-03-05T14:40:00+07:00", "afterLock": true, "reason": "tutor sick" } ] }
  ]
}
```
(Trimmed: three of the five lessons and some null fields are shown.)

## Layout
```
data/                          spreadsheet export used as seed
src/main/resources/db/migration/
  V1__schema.sql               tables + no-double-booking exclusion constraints
  V2__lesson_changes.sql       cancel/reschedule columns, append-only lesson_change
  V3__drop_override_reason.sql Monday and 6/day became hard rules
src/main/java/.../lesson/      book, cancel, reschedule, no-show (LessonService); rules (CenterRules)
src/main/java/.../schedule/    GET /schedule and /tutors/{id}/schedule (DayScheduleQuery)
src/main/java/.../seed/        CSV import that keeps and reports rule violations
src/main/java/.../reference/   lookup lists and simulated now for the UI
src/main/resources/static/     the UI (one HTML file)
```
