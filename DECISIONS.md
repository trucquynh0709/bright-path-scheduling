# Bright Path scheduling: decisions

**TL;DR.** I built one feature: **the lesson lifecycle**. You can book, cancel, mark a no-show and reschedule a lesson. The database makes it impossible to put a student, tutor or room in two places at once. A lesson the tutor was already told about is never overwritten. It gets a new status and a new history entry. Each tutor has one URL showing their day as it is now, with the changes made after the 16:00 lock called out. The real spreadsheet export is imported with its rule violations kept and flagged.

**Simulated "now": Thursday 2026-03-05 14:00 (+07:00).** This is inside the seed week and two hours before the 16:00 lock for Friday. Thursday's own lessons are already locked, so late-cancel and after-lock cases can be shown on real seed rows. It is set in `application.yml` (`app.now`, or the `APP_NOW` environment variable).

---

## 1. Reading the brief

### What is real pain, and what is only mentioned
| Real pain (owner said "never again", or it happens every day) | Only mentioned (a rule, with no complaint attached) |
|---|---|
| A student booked in two places at once | Billing: who pays for a late cancel or a no-show |
| Cancellations reaching a tutor who has already left; three messages, no way to tell which one is true | Mai's 8-week exit, the ~200 families, the 12 tutors (context, not features) |
| Changes after the 16:00 lock must be *visible*, not silent overwrites | Opening hours, subjects |

### Questions I would ask the owner first, and how the answers would change the build
1. **Is the "exam pair" (2 students, 1 tutor, 1 room, half price) an official product or Mai's workaround?** If it is official, I add a `group_session` table that one or more students join, and the overlap rules apply to the session, not the student row. If it is a workaround, it stays blocked (current behaviour).
2. **Who may go over 6 lessons per day or book on a Monday, and does the owner want to approve it?** Today anyone can, by giving a reason. If only the owner may, `overrideReason` becomes an approval step with a role.
3. **When the *tutor* cancels inside the 4 hours (L017, "tutor sick"), does the family still pay?** The rule as written assumes the family cancels. Today the code charges in both cases (see weak points). If the answer is "no", I add a `cancelled_by` field (family, tutor or center) and the fee applies only to family cancellations.
4. **Does a reschedule inside the 4 hours count as a late cancel?** Today it is free, which is a loophole. If it should be charged, `reschedule` records `late_cancel` on the original lesson.
5. **How does a tutor receive the schedule today (WhatsApp group, individual chats), and do tutors have smartphones?** The answer decides the notification channel, which is the next feature.
6. **Is a student's name unique?** The export has no student ID, so I use the name as the identity. Two children with the same name would be merged.

### Where the brief contradicts itself
- **"One room, one lesson" and "one tutor, one student" vs. the exam pairs.** Mai deliberately puts two students with the same tutor, in the same room, at the same time (L009/L010). Both rules are broken at once.
- **"Max 6 lessons per day" vs. what actually runs.** T1 has 7 lessons on Fri 03-06 (L018–L027).
- **"Closed on Monday" vs. L032.** It is on Monday 03-09, "moved from Sunday at the family's request".
- **"Tutor still paid on a late cancel" vs. L017.** That cancellation is the tutor's own ("tutor sick"), so the rule cannot be applied literally.
- **"12 tutors, 6 rooms" vs. the export.** It has 3 tutors and uses 3 rooms. I seed R1–R6 and only the 3 tutors I have.
- **"Student never in two places" vs. the data.** It has already happened (L007/L008 at Wed 09:00). L008's note even says "family confirmed".

### Assumptions I made, and what I did with each contradiction
- **Overlaps are hard rules (no override).** That is the one thing the owner said must never happen again, and an exam pair cannot be told apart from a mistake without a group concept. Exam pairs are therefore **blocked** until the owner answers Q1.
- **Monday and 6 per day are soft rules.** Mai breaks them on purpose when stuck, and blocking her would push bookings back into the spreadsheet. They are allowed only with an `overrideReason`, which is stored on the lesson.
- **All times are center time** (Asia/Ho_Chi_Minh). Lessons are 60 or 90 minutes. Rooms are R1–R6.
- **The lock moment for a lesson is 16:00 the day before it.** Any change at or after that moment is a change the tutor must be told about.
- **A cancellation releases the slot even when it is late** (charged). **A no-show holds the slot.** Both are as the brief says.
- **The seed is history, not a set of requests to validate.** Every row is imported, violations included (see §3).

---

## 2. Choosing the feature

| # | Candidate | Pain it solves |
|---|---|---|
| 1 | Booking that cannot double-book a student, tutor or room | Student in two places: **must never happen again** |
| 2 | Cancel and reschedule without overwriting, with history and after-lock flags | Three messages, which one is true? Changes must be visible |
| 3 | Per-tutor daily schedule as the single source of truth | Replaces the WhatsApp message chain |
| 4 | 16:00 lock and push notifications to tutors (WhatsApp, Zalo, SMS) | Tutor already left before hearing about the cancellation |
| 5 | Billing: 4h rule, no-show charges, tutor pay | Rule only mentioned; nobody complained about money |
| 6 | Admin: tutors, rooms, students, families, opening hours | Mai's handover; foundation, not a daily pain |

**Chosen: the lesson lifecycle**, which is #1 and #2 as one feature, with #3 as its read side. It is one feature because both real pains live in the same place: the `lesson` row and how it is allowed to change.
- #1 alone still leaves cancellations as silent edits.
- #2 without #1 has nothing correct to change.
- #3 is a single read endpoint over the same data. It is also how you check that #2 works.

**Deliberately left out:**
- **#4 Notifications.** Sending needs a channel decision (Q5) and possibly a paid WhatsApp Business account, and pushing a wrong schedule faster does not help. The data it needs is already there: a notifier only has to read `lesson_change WHERE after_lock` and quote the `revision`. No schema change is needed.
- **#5 Billing.** It is not a pain point. `late_cancel`, `no_show` and `cancelled_at` are stored at the moment of the event, so invoices can be built later without guessing.
- **#6 Admin CRUD.** The seed covers the demo. Tutors and rooms change rarely and SQL is fine for now.
- **Exam pairs as group sessions.** Blocked until Q1 is answered.
- **Auth, UI.** The API is usable with curl or Postman. A UI is next after notifications.

---

## 3. Design

### Data model
```
tutor(id 'T1', name, subject, phone)
room(id 'R1'..'R6')
student(id, full_name UNIQUE)          -- the export has names only (Q6)

lesson(
  id, external_ref 'L001' (spreadsheet id, NULL for new lessons),
  student_id, tutor_id, room_id, starts_at, ends_at,
  status        booked | cancelled | rescheduled | no_show,
  cancelled_at, late_cancel,           -- 4h decision, stored at cancel time
  replaces_lesson_id UNIQUE,           -- a reschedule points to the lesson it replaces
  override_reason,                     -- why a soft rule was knowingly broken
  legacy_conflict,                     -- imported row that overlapped an earlier one
  note, created_at)

lesson_change(lesson_id, change, changed_at, after_lock, reason)   -- append-only
import_issue(external_ref, rule, detail)                            -- what the import found
```

### A lesson cancelled or moved after the tutor was told
The core decision is that **a lesson row is never moved and never deleted**.
- **Cancel:** `status` becomes `cancelled`, with `cancelled_at` and `late_cancel`. The row stays on the tutor's schedule, shown as cancelled.
- **Reschedule:** the original becomes `rescheduled` and a **new** row is booked with `replaces_lesson_id = original`. The tutor who was told "Sat 09:00" still sees that lesson, sees it was moved, and sees where it went (`replacedByLessonId`, `replacedByStartsAt`). A chain can only ever have one `booked` lesson, because `replaces_lesson_id` is UNIQUE and only `booked` rows can be moved. A stale id therefore gets 409.
- **Every change appends to `lesson_change`** with `after_lock` = "made at or after 16:00 the day before the lesson". A trigger rejects UPDATE and DELETE on that table, so history a tutor may have seen cannot be rewritten.
- **The schedule endpoint reports `revision`** (the number of changes that day) and **`changedAfterLock`**. A message can say "rev 3", and the tutor can check that it is current.

Editing the row in place was rejected. That is exactly the silent overwrite the brief forbids, and it would lose what the tutor was told.

### Which rules live in the database and which in code
| Rule | Where | Why |
|---|---|---|
| No overlapping lessons for the same student, tutor or room | **DB**: `EXCLUDE USING gist` on `tstzrange(starts_at, ends_at)`, `WHERE status IN ('booked','no_show') AND NOT legacy_conflict` | This is the never-again rule. It must also hold under two receptionists booking at the same moment, a code bug, or a manual SQL fix. Only the DB can promise that. |
| 60 or 90 minutes; valid status; `cancelled_at` and `late_cancel` set exactly when cancelled; one replacement per lesson; append-only history | **DB**: CHECK, UNIQUE, trigger | These are row invariants, cheap to state and impossible to forget. |
| Closed Monday, max 6 per tutor per day | **Code** (`LessonService`), overridable with a reason | They need a per-day count and a human override. The tutor row is locked `FOR UPDATE` while booking, so two concurrent bookings cannot both see "5 today". |
| Not in the past; 4h late-cancel; 16:00 lock; started lessons can only be marked no-show | **Code** | They depend on "now". The `Clock` is injected, so they are testable and use the simulated date. |

The code also checks overlaps *before* inserting, but only to return a helpful 409 that names the blocking lesson, for example `L019`. The constraint is the guarantee.

### Dirty seed data
Hard constraints would reject the real export. I did not loosen them for everyone. Instead:
- An imported row that overlaps an **earlier imported row** is stored with `legacy_conflict = true`. It stays visible as history but holds no slot. That is L008, L010 and L034. The first row of each pair keeps the slot.
- Monday and over-6 rows are stored as they are (L032, L027).
- **Every violation is written to `import_issue` and printed at startup**, so the owner gets a list to review rather than a silently "fixed" dataset:
  ```
  L008 no_student_double_booking: overlaps L007
  L010 no_room_double_booking: overlaps L009
  L010 no_tutor_double_booking: overlaps L009
  L027 max_6_lessons_per_tutor_per_day: T1 already has 6 on 2026-03-06
  L032 closed_on_monday: lesson on 2026-03-09
  L034 no_tutor_double_booking: overlaps L033
  ```
- The two cancellations in the export get a history entry and the 4h rule (L005 free, L017 charged).

### API
| Method and path | Purpose | Main errors |
|---|---|---|
| `POST /lessons` | Book a lesson: `{studentId, tutorId, roomId, startsAt, durationMin, overrideReason?}` | 409 clash (lists the blocking lessons), 422 soft rule without a reason, past, or wrong length; 404 unknown id |
| `POST /lessons/{id}/cancel` | Cancel: `{reason?}`. Applies the 4h rule and frees the slot | 409 not booked; 422 already started |
| `POST /lessons/{id}/no-show` | Mark a no-show: keeps the slot and the charge | 422 not started yet |
| `POST /lessons/{id}/reschedule` | Move a lesson: `{startsAt, durationMin?, tutorId?, roomId?, reason?, overrideReason?}`. Returns the **new** lesson | Same as booking; the whole move rolls back on error |
| `GET /tutors/{id}/schedule?date=` | The tutor's day: all lessons including cancelled and moved ones, `locked`, `changedAfterLock`, `revision` | 404 unknown tutor |

Errors are RFC 9457 problem JSON with a machine-readable `rule`, using the same names as the DB constraints.

### An endpoint I considered and rejected: `PUT /lessons/{id}` (and `PATCH`)
This is the "obvious" generic update. Rejected for three reasons:
1. It makes silently overwriting a time or a room the easiest thing to do, which the brief forbids after the lock.
2. It loses what the tutor was told.
3. One endpoint would have to infer intent from a diff: a status change is a cancel, a time change is a reschedule. That would hide the 4h and no-show rules inside conditionals.

Explicit verbs (`/cancel`, `/reschedule`, `/no-show`) each have exactly one rule set and one history entry. For the same reason `DELETE /lessons/{id}` does not exist.

---

## 4. Looking back

### With one more week
1. **Notifications** (the next feature). On every after-lock change, and at 16:00 for tomorrow, send each affected tutor one message with the new state and the `revision`. Add delivery and acknowledgement tracking, so Mai can see which tutor has *not* seen a cancellation and call them.
2. **`cancelled_by`** (family, tutor or center), so a tutor-sick cancel does not charge the family (Q3). Also decide the late-reschedule fee (Q4).
3. **Group sessions** for exam pairs, if the owner confirms they are a product (Q1).
4. **A minimal UI for Mai** (day grid per room and tutor) and a read-only tutor page, plus auth. Today anyone with network access can call the API.
5. **Real student and family records** with IDs and phone numbers, replacing name-as-identity.

### Weak points I know about
- **The tutor-sick case charges the family.** L017 is flagged `late_cancel = true` although the tutor cancelled. The data is right (it was inside 4h), but the billing meaning is wrong until `cancelled_by` exists.
- **Rescheduling inside 4h is free.** This is a loophole until the owner decides (Q4).
- **Student identity is the name.** Two students with the same name would merge on import.
- **Seed history is partial.** The export only timestamps cancellations, so other imported lessons have no "booked" entry and are treated as part of the original schedule.
- **`revision` counts changes; it is not a monotonic version per message sent.** It cannot tell whether the tutor actually saw rev 2. That needs the notification log.
- **No auth or audit of *who* made a change**, only when and why.
- **No opening-hours rule.** A lesson at 03:00 would be accepted. The center's hours are not in the brief.
- **The race path loses detail.** If two concurrent bookings collide, the second gets a 409 naming only the rule, not the other lesson, because the transaction is aborted. It is rare and still correct.

### How AI helped
I used Claude Code (Opus) throughout as a pair programmer. It:
- separated real pain from rules that are only mentioned, and found the contradictions in the seed by reading it row by row;
- drafted the schema, importer, services and tests;
- ran everything against Postgres in Docker and wrote the commit messages.

Every rule decision above (hard vs. soft rules, blocking exam pairs, new row on reschedule, the after-lock definition) was discussed and chosen, not accepted by default. I can explain or change any of it live.

### AI output I rejected, and why rejecting it was right
- **A test that tested nothing.** While writing the booking tests, the AI produced `cancelledLessonFreesItsSlot`, which built an empty list and asserted that it was empty. It would have shown green forever. I replaced it with two real tests: a cancelled seed lesson (L005) frees its slot, and a no-show (L015) keeps it. The second one guards the exact distinction the brief makes, and a fake test would have hidden a regression there.
- **Killing whatever was on port 8080.** The AI's first "start the app and check it" command ended with a `taskkill` of the process listening on port 8080. That port turned out to belong to an unrelated service on my machine (`AgentService`), not our app. The command was stopped before it ran. The app moved to 8081, and from then on the stop step first checked that the listening process was our `java`. A dev tool should never kill a process it did not start.

### Time
Commit timestamps: **16:30–17:51** (setup, schema, import, booking, tests), then a break, then **20:50–21:41** (history, cancel, reschedule, schedule), plus about 20 minutes for these documents. That is roughly **2h30 of work**, at the limit. Two Docker Desktop crashes cost about 10 minutes of it.
