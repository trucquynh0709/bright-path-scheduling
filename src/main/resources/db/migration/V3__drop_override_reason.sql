-- Monday and the 6-lessons-per-day limit are now enforced without exception: the brief says the
-- owner wants the daily limit enforced, and on Monday the rooms are being cleaned. With no
-- override path left, the column that stored override reasons has nothing to hold.
-- Imported rows that broke these rules are still kept; they are listed in import_issue.
ALTER TABLE lesson DROP COLUMN override_reason;
