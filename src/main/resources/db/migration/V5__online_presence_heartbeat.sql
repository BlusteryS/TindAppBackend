ALTER TABLE users ADD COLUMN IF NOT EXISTS online_refreshed_at TIMESTAMPTZ;

UPDATE users
SET is_online = FALSE,
    online_refreshed_at = NULL,
    last_seen = COALESCE(last_seen, NOW()),
    updated_at = NOW()
WHERE is_online = TRUE;

CREATE INDEX IF NOT EXISTS idx_users_online_refreshed_at
    ON users(online_refreshed_at)
    WHERE is_online = TRUE;
