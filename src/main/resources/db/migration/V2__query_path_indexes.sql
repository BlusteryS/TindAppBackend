CREATE INDEX IF NOT EXISTS idx_users_matching_live
    ON users (city, gender, is_verified, is_online DESC, last_seen DESC, updated_at DESC, id DESC)
    WHERE is_visible = TRUE AND allow_messages = TRUE AND is_banned = FALSE;

CREATE INDEX IF NOT EXISTS idx_users_matching_global
    ON users (gender, is_verified, is_online DESC, last_seen DESC, updated_at DESC, id DESC)
    WHERE is_visible = TRUE AND allow_messages = TRUE AND is_banned = FALSE;

CREATE INDEX IF NOT EXISTS idx_chats_user1_active_updated
    ON chats (user1_id, is_active, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chats_user2_active_updated
    ON chats (user2_id, is_active, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chats_participants_active_reason_updated
    ON chats (participant_low_id, participant_high_id, is_active, closure_reason, updated_at DESC);
