CREATE TABLE IF NOT EXISTS mythic_membership (
    player_uuid VARCHAR(36) NOT NULL,
    membership_id VARCHAR(191) NOT NULL,
    scope VARCHAR(191) NOT NULL,
    expires_at_millis BIGINT,
    PRIMARY KEY (player_uuid, membership_id, scope)
);

CREATE TABLE IF NOT EXISTS mythic_membership_player_name (
    player_name_lower VARCHAR(16) PRIMARY KEY,
    player_name VARCHAR(16) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    last_seen_millis BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mythic_membership_player_name_uuid
    ON mythic_membership_player_name (player_uuid);
