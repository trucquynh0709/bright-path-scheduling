# Bright Path scheduling: decisions

**TL;DR.** I built one feature: **the lesson lifecycle**. You can book, cancel, reschedule and mark a no-show.
- The database makes it impossible to put a student, tutor or room in two places at once.
- A lesson the tutor was already told about is never overwritten. It gets a new status and a history entry, flagged if it happened after the 16:00 cut-off.
- The real export is imported with its rule breaks kept and reported.

**Pinned date: Thursday 2026-03-05, 14:00 (+07:00).** This is set in `application.yml` and overridable with `APP_NOW`.
- It sits inside the seed week.
- Thursday's own lessons are already past the cut-off, and Friday's cut-off is two hours away, so both cases can be shown on real rows.
- The export was taken on 03-10. Rows dated after "now" are treated as upcoming.
- One cancellation in the export (L017, cancelled at 14:40) is timestamped after "now". It is imported as history anyway.

## 1. Reading the situation

**What actually hurts** (said in people's own words):
- **Owner:** "a student booked into two places at once … if the system allows it, the system is broken."
- **Owner:** "open the laptop and see today. Not scroll. See it."
- **Receptionist and tutor:** cancellations arrive at night, the sheet is fixed in the morning, the tutor has already left; "a message, then a correction, then a third … I do not always know which one is real."

**Only mentioned:** billing details, the 12 tutors and ~200 families, telling the next family a slot opened. They are real, but nobody described them as the daily pain.

**Questions for the owner first, and what each answer changes:**
1. **Are exam pairs (2 students, 1 tutor, 1 room, half price) a product?** Mai says she does it "most weeks". If yes, I add a group-session table that students join, and the overlap rules apply to the session. If no, it stays blocked (today's behaviour).
2. **When the *tutor* cancels inside 4h (L017, "tutor sick"), does the family pay?** The rule assumes the family cancels. If not, I add `cancelled_by`, and the fee applies to family cancellations only.
3. **Is a move inside 4h a late cancel?** It is free today, which is a loophole. If yes, the reschedule records the fee on the original lesson.
4. **How do tutors get their day today, and will they open a link?** This decides the notification channel, which is the next feature.
5. **What are the exact opening hours?** "Mid-morning to mid-evening" is too vague to enforce literally. I enforce 09:00–21:30, the range the export actually ran. The owner's answer changes two constants in `CenterRules`.
6. **Is a student's name unique?** The export has no ID, so the name is the identity.

**Where the brief argues with itself, and the reading I chose:**
- **"Max 6 per day" vs "Mai breaks this rule when she is desperate."** The brief adds "the owner wants it enforced", so **I side with the owner: hard limit, no override.**
- **"One room, one lesson" and "one tutor, one student" vs exam pairs most weeks.** **Blocked** until Q1 is answered. The tool will stop something Mai does weekly, and the owner needs to know that before go-live.
- **"Closed Monday" vs L032.** L032 is a real Monday lesson, "moved from Sunday at the family's request". New Monday bookings are refused. L032 is kept as history.
- **"Tutor still paid on a late cancel" vs L017.** L017 is the tutor's own cancellation (Q2).
- **"Never in two places" vs L007/L008.** It already happened, and L008's note says "family confirmed".
- **"12 tutors" vs the export.** It has 3 tutors and uses 3 of 6 rooms.

**Assumptions I invented:**
- All times are centre time (Asia/Ho_Chi_Minh).
- A lesson's cut-off is 16:00 the day before it.
- Opening hours are 09:00–21:30: the earliest start and latest end in the export (L027 ends 21:30).
- The seed is history to keep, not requests to validate.

## 2. Choosing what to build

**Candidates, one line each:**
1. Booking that cannot double-book a student, tutor or room.
2. Cancel and reschedule that never overwrite, with history and after-cut-off flags.
3. Today's schedule on one screen: the centre for the owner, a tutor's own view for each tutor.
4. Notify tutors of changes (WhatsApp, Zalo, SMS), with read receipts.
5. Tell waiting families when a slot opens.
6. Billing and tutor pay (4h rule, no-shows).
7. Admin for tutors, rooms, families, opening hours.

**Picked: the lesson lifecycle (1 + 2, with 3 as its read side).**
- Both real pains live in one place: the `lesson` row and how it may change.
- 1 alone still leaves cancellations as silent edits. 2 without 1 has nothing correct to change.
- 3 is read-only over the same data, and it is how anyone checks that 2 worked.

**What I leave broken by choosing it:**
- **Nobody is pushed a message.** A night cancellation is recorded the moment it is entered, and it is visible on the tutor's link and in "today". But a tutor who does not look can still drive in. Feature 4 closes this. It only needs to read `lesson_change WHERE after_lock`, with no schema change.
- **Exam pairs are refused**, Mai's weekly practice included (Q1).
- **No waiting-family messages** (5), **no invoices** (6), **no admin screens** (7). The fee decision is stored (`late_cancel`), so billing can be added later without guessing.

## 3. Design

**Data model** (`V1`–`V3` migrations):
```
tutor(id, name, subject, phone)    room(id R1..R6)    student(id, full_name UNIQUE)
lesson(id, external_ref 'L001', student_id, tutor_id, room_id, starts_at, ends_at,
       status booked|cancelled|rescheduled|no_show, cancelled_at, late_cancel,
       replaces_lesson_id UNIQUE, legacy_conflict, note)
lesson_change(lesson_id, change, changed_at, after_lock, reason)   -- append-only
import_issue(external_ref, rule, detail)
```

**A booking cancelled or moved after the tutor was told:**
- **Nothing is edited away.** A cancel sets `status = cancelled`, with `cancelled_at` and `late_cancel`. The row stays on the schedule.
- **A move** marks the original `rescheduled` and books a **new** row with `replaces_lesson_id`. The tutor still sees "Sat 09:00", sees that it moved, and sees where it went. `replaces_lesson_id` is UNIQUE, so a chain cannot fork.
- **Every change appends to `lesson_change`** with `after_lock`. A trigger rejects UPDATE and DELETE, so what a tutor may have seen cannot be rewritten.
- **Each day reports `revision` and `changedAfterLock`.** "Which message is real" gets a checkable answer.

**Which rules live in the database and which in code:**

| Rule | Where | Why |
|---|---|---|
| No overlap for the same student, tutor or room | **DB**: `EXCLUDE USING gist` on `tstzrange(starts_at, ends_at)` for `booked` and `no_show` rows | The owner's never-again rule. It must hold under concurrent requests, code bugs and manual SQL. A cancel or move frees the slot; a no-show does not. |
| 60 or 90 min; valid status; `late_cancel` exactly when cancelled; one replacement per lesson; append-only history | **DB**: CHECK, UNIQUE, trigger | Row invariants, cheap to state. |
| Closed Monday; opening hours 09:00–21:30; max 6 per tutor per day | **Code** (`LessonService`), no override | A day-of-week, a time window and a per-day count. The tutor row is locked `FOR UPDATE`, so two bookings cannot both see "5". They stay out of the DB so the real export (L027, L032) still imports, and so the assumed hours can change without a migration. |
| Not in the past; 4h fee; cut-off flag; started lessons can only be no-shows | **Code** | They depend on "now", which is an injected `Clock`. |

**Dirty seed.**
- Hard constraints would reject the real export, and I did not loosen them for new bookings.
- A row overlapping an earlier imported row is stored with `legacy_conflict = true`: kept, but it holds no slot.
- Every violation goes to `import_issue` and the startup log, which shows 6 issues for L008, L010, L027, L032 and L034.

**API.**
- `POST /lessons`
- `POST /lessons/{id}/cancel`, `/no-show` and `/reschedule`
- `GET /schedule?date=` (centre) and `GET /tutors/{id}/schedule?date=` (tutor)
- Errors are problem JSON with a `rule` named like the DB constraint. A clash also names the blocking lesson, for example `L019`.

**Rejected endpoint: `PUT /lessons/{id}`.**
- It makes the silent overwrite the easiest call.
- It loses what the tutor was told.
- It would have to guess from a diff whether an edit is a cancel or a move.
- Explicit verbs each carry one rule set and one history entry. For the same reason there is no `DELETE`.

## 4. Reflect

**With another week:**
1. Push each after-cut-off change, and the 16:00 schedule, to the tutor. Track whether they opened it, so Mai sees who has *not* seen a cancellation.
2. Add `cancelled_by`, so a tutor's own cancel does not charge the family. Decide the late-move fee (Q2, Q3).
3. Group sessions for exam pairs, if the owner says yes (Q1).
4. Auth, and real student and family records with IDs.

**What I know is weak:**
- **L017 (tutor sick) is marked as charging the family.** The data is right; the billing meaning is wrong until `cancelled_by` exists.
- **A move inside 4h is free.**
- **Name-as-identity** would merge two students with the same name.
- **Only cancellations have history in the import**, because the export has no other timestamps.
- **`revision` counts changes; it cannot tell whether a tutor saw them.**
- **No auth**, and no record of *who* made a change.
- **Opening hours are my assumption** (09:00–21:30), not a stated rule.
- **On a concurrent clash, the 409 names only the rule**, not the other lesson.

**Where the AI helped.** I used Claude Code throughout.
- It read the export row by row to find the rule breaks.
- It drafted the schema, importer, services, tests and the UI, and ran everything against Postgres in Docker.
- I chose the rule decisions above and can explain or change them.

**A suggestion I threw away.**
- **What it proposed:** the AI suggested letting Mai override Monday and the 6-per-day limit with a written reason, "because she breaks them when stuck". I first accepted that.
- **Why I dropped it:** re-reading the original brief, it says the owner *wants the limit enforced*. An override sides with the workaround the owner is trying to end, so I made both hard rules (`1d7a677`).
- **Why that is right:** the cost is that Mai will sometimes hear "no". That is the owner's call, and it is now stated in §1 ("where the brief argues with itself") rather than hidden in a reason field.
- **A smaller case:** the AI once produced a test that asserted nothing, and I replaced it with two real ones (cancel frees the slot, no-show keeps it).

**Time.**
- **Inside the 2.5h box:** setup to the first DECISIONS draft, about 16:30–17:51 and 20:50–21:44.
- **After it, in separate commits** from `9adf4b4` on:
  - the UI and its restyles;
  - the centre "today" endpoint and screen (`0a2bc58`, `3622e43`, `ae25163`, `0590951`);
  - two corrections made after I re-read the original brief: Monday and 6-per-day as hard rules (`1d7a677`), and opening hours (`e6ea5a8`).

  You can judge the timeboxed work on its own by reading up to `94904a4`.
