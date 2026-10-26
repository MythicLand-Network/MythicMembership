ALTER TABLE mythic_membership ADD COLUMN IF NOT EXISTS paused_by TEXT;
ALTER TABLE mythic_membership ADD COLUMN IF NOT EXISTS paused_remaining_millis BIGINT;
