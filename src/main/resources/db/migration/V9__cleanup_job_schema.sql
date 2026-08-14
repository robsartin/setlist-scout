-- Phase B design review (#95) cleanup: D2 + D6.
--
-- D2: location_fingerprint was write-only dead state -- written on enqueue/redue/backfill but
-- never read for comparison, and onSettingsChanged already re-dues every job unconditionally.
-- Remove it (this also makes scan_job and expand_job column-identical; see #94).
ALTER TABLE scan_job DROP COLUMN location_fingerprint;

-- D6: poller error messages were truncated to varchar(255); API/LLM failures carry long bodies,
-- so a parked job's stored reason was often a chopped fragment. Widen to text (pollers still
-- truncate in application code to a generous bound so row size stays bounded).
ALTER TABLE scan_job   ALTER COLUMN last_error TYPE text;
ALTER TABLE expand_job ALTER COLUMN last_error TYPE text;
