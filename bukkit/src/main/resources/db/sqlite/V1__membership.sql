CREATE TABLE IF NOT EXISTS mythic_membership (
    player_uuid TEXT NOT NULL,
    membership_id TEXT NOT NULL,
    scope TEXT NOT NULL,
    expires_at_millis INTEGER,
    PRIMARY KEY (player_uuid, membership_id, scope)
);

CREATE TABLE IF NOT EXISTS mythic_membership_player_name (
    player_name_lower TEXT PRIMARY KEY,
    player_name TEXT NOT NULL,
    player_uuid TEXT NOT NULL,
    last_seen_millis INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mythic_membership_player_name_uuid
    ON mythic_membership_player_name (player_uuid);
