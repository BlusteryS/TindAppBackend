CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    vk_id BIGINT UNIQUE,
    age INT,
    birth_date DATE,
    first_name TEXT NOT NULL DEFAULT '',
    last_name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT NOT NULL DEFAULT '',
    country TEXT NOT NULL DEFAULT '',
    city TEXT NOT NULL DEFAULT '',
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    was_verified BOOLEAN NOT NULL DEFAULT FALSE,
    is_online BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen TIMESTAMPTZ,
    bio TEXT NOT NULL DEFAULT '',
    gender TEXT,
    is_visible BOOLEAN NOT NULL DEFAULT TRUE,
    allow_messages BOOLEAN NOT NULL DEFAULT TRUE,
    subscription_is_active BOOLEAN NOT NULL DEFAULT FALSE,
    subscription_type TEXT,
    subscription_expires_at TIMESTAMPTZ,
    balance INT NOT NULL DEFAULT 0,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    rewards JSONB NOT NULL DEFAULT '{}'::jsonb,
    profile_cost INT NOT NULL DEFAULT 0,
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    is_banned BOOLEAN NOT NULL DEFAULT FALSE,
    ban_reason TEXT,
    banned_at TIMESTAMPTZ,
    native_language TEXT NOT NULL DEFAULT 'ru',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

UPDATE users
SET first_name = COALESCE(first_name, ''),
    last_name = COALESCE(last_name, ''),
    avatar_url = COALESCE(avatar_url, ''),
    country = COALESCE(country, ''),
    city = COALESCE(city, ''),
    is_verified = COALESCE(is_verified, FALSE),
    was_verified = COALESCE(was_verified, FALSE),
    is_online = COALESCE(is_online, FALSE),
    bio = COALESCE(bio, ''),
    is_visible = COALESCE(is_visible, TRUE),
    allow_messages = COALESCE(allow_messages, TRUE),
    subscription_is_active = COALESCE(subscription_is_active, FALSE),
    balance = COALESCE(balance, 0),
    settings = COALESCE(settings, '{}'::jsonb),
    rewards = COALESCE(rewards, '{}'::jsonb),
    profile_cost = 0,
    is_admin = COALESCE(is_admin, FALSE),
    is_banned = COALESCE(is_banned, FALSE),
    native_language = COALESCE(NULLIF(native_language, ''), 'ru'),
    updated_at = COALESCE(updated_at, NOW()),
    created_at = COALESCE(created_at, NOW());

ALTER TABLE users ALTER COLUMN first_name SET DEFAULT '';
ALTER TABLE users ALTER COLUMN last_name SET DEFAULT '';
ALTER TABLE users ALTER COLUMN avatar_url SET DEFAULT '';
ALTER TABLE users ALTER COLUMN country SET DEFAULT '';
ALTER TABLE users ALTER COLUMN city SET DEFAULT '';
ALTER TABLE users ALTER COLUMN is_verified SET DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN was_verified SET DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN is_online SET DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN bio SET DEFAULT '';
ALTER TABLE users ALTER COLUMN is_visible SET DEFAULT TRUE;
ALTER TABLE users ALTER COLUMN allow_messages SET DEFAULT TRUE;
ALTER TABLE users ALTER COLUMN subscription_is_active SET DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN balance SET DEFAULT 0;
ALTER TABLE users ALTER COLUMN settings SET DEFAULT '{}'::jsonb;
ALTER TABLE users ALTER COLUMN rewards SET DEFAULT '{}'::jsonb;
ALTER TABLE users ALTER COLUMN profile_cost SET DEFAULT 0;
ALTER TABLE users ALTER COLUMN is_admin SET DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN is_banned SET DEFAULT FALSE;
ALTER TABLE users ALTER COLUMN native_language SET DEFAULT 'ru';
ALTER TABLE users ALTER COLUMN updated_at SET DEFAULT NOW();
ALTER TABLE users ALTER COLUMN created_at SET DEFAULT NOW();

CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY
);

ALTER TABLE chats ADD COLUMN IF NOT EXISTS type TEXT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS user1_id BIGINT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS user2_id BIGINT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS participant_low_id BIGINT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS participant_high_id BIGINT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS last_message JSONB;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS last_message_id TEXT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS unread_count INT NOT NULL DEFAULT 0;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS settings JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE chats ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE chats ADD COLUMN IF NOT EXISTS closed_by_user_id BIGINT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS closure_reason TEXT;
ALTER TABLE chats ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'chats'
          AND column_name = 'data'
    ) THEN
        UPDATE chats
        SET type = COALESCE(type, UPPER(NULLIF(data->>'type', ''))),
            user1_id = COALESCE(user1_id, NULLIF(data->>'user1_id', '')::BIGINT),
            user2_id = COALESCE(user2_id, NULLIF(data->>'user2_id', '')::BIGINT),
            last_message = COALESCE(last_message, data->'last_message'),
            last_message_id = COALESCE(last_message_id, NULLIF(data->'last_message'->>'id', '')),
            unread_count = COALESCE(unread_count, NULLIF(data->>'unread_count', '')::INT, 0),
            settings = CASE
                WHEN settings = '{}'::jsonb THEN COALESCE(data->'settings', '{}'::jsonb)
                ELSE settings
            END,
            is_active = COALESCE(is_active, NULLIF(data->>'is_active', '')::BOOLEAN, TRUE),
            created_at = COALESCE(created_at, NULLIF(data->>'created_at', '')::TIMESTAMPTZ, NOW()),
            updated_at = COALESCE(updated_at, NULLIF(data->>'updated_at', '')::TIMESTAMPTZ, NOW()),
            closed_by_user_id = COALESCE(closed_by_user_id, NULLIF(data->>'closed_by_user_id', '')::BIGINT),
            closure_reason = COALESCE(closure_reason, NULLIF(data->>'closure_reason', '')),
            closed_at = COALESCE(closed_at, NULLIF(data->>'closed_at', '')::TIMESTAMPTZ);

        ALTER TABLE chats DROP COLUMN IF EXISTS data;
    END IF;
END $$;

UPDATE chats
SET participant_low_id = CASE
        WHEN user1_id IS NOT NULL AND user2_id IS NOT NULL THEN LEAST(user1_id, user2_id)
        ELSE participant_low_id
    END,
    participant_high_id = CASE
        WHEN user1_id IS NOT NULL AND user2_id IS NOT NULL THEN GREATEST(user1_id, user2_id)
        ELSE participant_high_id
    END,
    unread_count = COALESCE(unread_count, 0),
    settings = COALESCE(settings, '{}'::jsonb),
    is_active = COALESCE(is_active, TRUE),
    created_at = COALESCE(created_at, NOW()),
    updated_at = COALESCE(updated_at, NOW());

CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY
);

ALTER TABLE messages ADD COLUMN IF NOT EXISTS chat_id TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS sender_id BIGINT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS text TEXT NOT NULL DEFAULT '';
ALTER TABLE messages ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'TEXT';
ALTER TABLE messages ADD COLUMN IF NOT EXISTS reply_to JSONB;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS reply_to_message_id TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS attachments JSONB;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS translations JSONB;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS is_read BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS is_edited BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE messages ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'messages'
          AND column_name = 'data'
    ) THEN
        UPDATE messages
        SET chat_id = COALESCE(chat_id, NULLIF(data->>'chat_id', '')),
            sender_id = COALESCE(sender_id, NULLIF(data->>'sender_id', '')::BIGINT),
            text = COALESCE(text, data->>'text', ''),
            type = COALESCE(type, UPPER(NULLIF(data->>'type', '')), 'TEXT'),
            reply_to = COALESCE(reply_to, data->'reply_to'),
            reply_to_message_id = COALESCE(reply_to_message_id, NULLIF(data->'reply_to'->>'message_id', '')),
            attachments = COALESCE(attachments, data->'attachments'),
            translations = COALESCE(translations, data->'translations'),
            is_read = COALESCE(is_read, NULLIF(data->>'is_read', '')::BOOLEAN, FALSE),
            is_edited = COALESCE(is_edited, NULLIF(data->>'is_edited', '')::BOOLEAN, FALSE),
            created_at = COALESCE(created_at, NULLIF(data->>'created_at', '')::TIMESTAMPTZ, NOW()),
            updated_at = COALESCE(updated_at, NULLIF(data->>'updated_at', '')::TIMESTAMPTZ, NOW());

        ALTER TABLE messages DROP COLUMN IF EXISTS data;
    END IF;
END $$;

UPDATE messages
SET text = COALESCE(text, ''),
    type = COALESCE(type, 'TEXT'),
    is_read = COALESCE(is_read, FALSE),
    is_edited = COALESCE(is_edited, FALSE),
    created_at = COALESCE(created_at, NOW()),
    updated_at = COALESCE(updated_at, NOW());

CREATE TABLE IF NOT EXISTS notifications (
    id TEXT PRIMARY KEY
);

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'SYSTEM';
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT '';
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS message TEXT NOT NULL DEFAULT '';
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS is_read BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS payload JSONB;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'notifications'
          AND column_name = 'data'
    ) THEN
        UPDATE notifications
        SET user_id = COALESCE(user_id, NULLIF(data->>'user_id', '')::BIGINT),
            type = COALESCE(type, UPPER(NULLIF(data->>'type', '')), 'SYSTEM'),
            title = COALESCE(title, data->>'title', ''),
            message = COALESCE(message, data->>'message', ''),
            is_read = COALESCE(is_read, NULLIF(data->>'is_read', '')::BOOLEAN, FALSE),
            payload = COALESCE(payload, data->'data'),
            created_at = COALESCE(created_at, NULLIF(data->>'created_at', '')::TIMESTAMPTZ, NOW());

        ALTER TABLE notifications DROP COLUMN IF EXISTS data;
    END IF;
END $$;

UPDATE notifications
SET type = COALESCE(type, 'SYSTEM'),
    title = COALESCE(title, ''),
    message = COALESCE(message, ''),
    is_read = COALESCE(is_read, FALSE),
    created_at = COALESCE(created_at, NOW());

CREATE TABLE IF NOT EXISTS subscriptions (
    id TEXT PRIMARY KEY
);

ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS type TEXT;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS start_date TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS end_date TIMESTAMPTZ;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS price DOUBLE PRECISION;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS payment_method TEXT;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS auto_renew BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS plan_id TEXT;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS vk_subscription_id TEXT;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS price_in_votes INT;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS next_bill_date TIMESTAMPTZ;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pending_cancel BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS cancel_reason TEXT;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS app_order_id INT;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'subscriptions'
          AND column_name = 'data'
    ) THEN
        UPDATE subscriptions
        SET user_id = COALESCE(user_id, NULLIF(data->>'user_id', '')::BIGINT),
            type = COALESCE(type, UPPER(NULLIF(data->>'type', ''))),
            status = COALESCE(status, UPPER(NULLIF(data->>'status', '')), 'ACTIVE'),
            start_date = COALESCE(start_date, NULLIF(data->>'start_date', '')::TIMESTAMPTZ, NOW()),
            end_date = COALESCE(end_date, NULLIF(data->>'end_date', '')::TIMESTAMPTZ),
            price = COALESCE(price, NULLIF(data->>'price', '')::DOUBLE PRECISION),
            payment_method = COALESCE(payment_method, UPPER(NULLIF(data->>'payment_method', ''))),
            auto_renew = COALESCE(auto_renew, NULLIF(data->>'auto_renew', '')::BOOLEAN, FALSE),
            plan_id = COALESCE(plan_id, NULLIF(data->>'plan_id', '')),
            vk_subscription_id = COALESCE(vk_subscription_id, NULLIF(data->>'vk_subscription_id', '')),
            price_in_votes = COALESCE(price_in_votes, NULLIF(data->>'price_in_votes', '')::INT),
            next_bill_date = COALESCE(next_bill_date, NULLIF(data->>'next_bill_date', '')::TIMESTAMPTZ),
            pending_cancel = COALESCE(pending_cancel, NULLIF(data->>'pending_cancel', '')::BOOLEAN, FALSE),
            cancel_reason = COALESCE(cancel_reason, NULLIF(data->>'cancel_reason', '')),
            app_order_id = COALESCE(app_order_id, NULLIF(data->>'app_order_id', '')::INT);

        ALTER TABLE subscriptions DROP COLUMN IF EXISTS data;
    END IF;
END $$;

UPDATE subscriptions
SET status = COALESCE(status, 'ACTIVE'),
    start_date = COALESCE(start_date, NOW()),
    auto_renew = COALESCE(auto_renew, FALSE),
    pending_cancel = COALESCE(pending_cancel, FALSE);

CREATE TABLE IF NOT EXISTS reports (
    id TEXT PRIMARY KEY
);

ALTER TABLE reports ADD COLUMN IF NOT EXISTS reporter_id BIGINT;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS target_id BIGINT;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS chat_id TEXT;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS message_id TEXT;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS reason TEXT;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT '';
ALTER TABLE reports ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'PENDING';
ALTER TABLE reports ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'reports'
          AND column_name = 'data'
    ) THEN
        UPDATE reports
        SET reporter_id = COALESCE(reporter_id, NULLIF(data->>'reporter_id', '')::BIGINT),
            target_id = COALESCE(target_id, NULLIF(data->>'target_id', '')::BIGINT),
            chat_id = COALESCE(chat_id, NULLIF(data->>'chat_id', '')),
            message_id = COALESCE(message_id, NULLIF(data->>'message_id', '')),
            reason = COALESCE(reason, UPPER(NULLIF(data->>'reason', ''))),
            description = COALESCE(description, data->>'description', ''),
            status = COALESCE(status, UPPER(NULLIF(data->>'status', '')), 'PENDING'),
            created_at = COALESCE(created_at, NULLIF(data->>'created_at', '')::TIMESTAMPTZ, NOW());

        ALTER TABLE reports DROP COLUMN IF EXISTS data;
    END IF;
END $$;

UPDATE reports
SET description = COALESCE(description, ''),
    status = COALESCE(status, 'PENDING'),
    created_at = COALESCE(created_at, NOW());

CREATE TABLE IF NOT EXISTS blacklist (
    id TEXT PRIMARY KEY
);

ALTER TABLE blacklist ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE blacklist ADD COLUMN IF NOT EXISTS blocked_user_id BIGINT;
ALTER TABLE blacklist ADD COLUMN IF NOT EXISTS reason TEXT;
ALTER TABLE blacklist ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'blacklist'
          AND column_name = 'data'
    ) THEN
        UPDATE blacklist
        SET user_id = COALESCE(user_id, NULLIF(data->>'user_id', '')::BIGINT),
            blocked_user_id = COALESCE(blocked_user_id, NULLIF(data->>'blocked_user_id', '')::BIGINT),
            reason = COALESCE(reason, NULLIF(data->>'reason', '')),
            created_at = COALESCE(created_at, NULLIF(data->>'created_at', '')::TIMESTAMPTZ, NOW());

        ALTER TABLE blacklist DROP COLUMN IF EXISTS data;
    END IF;
END $$;

UPDATE blacklist
SET created_at = COALESCE(created_at, NOW());

CREATE INDEX IF NOT EXISTS idx_users_city ON users(city);
CREATE INDEX IF NOT EXISTS idx_users_gender_age ON users(gender, age);
CREATE INDEX IF NOT EXISTS idx_users_is_verified ON users(is_verified);
CREATE INDEX IF NOT EXISTS idx_users_is_visible ON users(is_visible);
CREATE INDEX IF NOT EXISTS idx_users_allow_messages ON users(allow_messages);
CREATE INDEX IF NOT EXISTS idx_users_is_online ON users(is_online);
CREATE INDEX IF NOT EXISTS idx_users_subscription_active ON users(subscription_is_active);
CREATE INDEX IF NOT EXISTS idx_users_updated_at ON users(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_matching ON users(is_visible, allow_messages, city, gender, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chats_user1_updated ON chats(user1_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chats_user2_updated ON chats(user2_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chats_active_updated ON chats(is_active, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chats_type_active ON chats(type, is_active, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chats_participants_type ON chats(participant_low_id, participant_high_id, type, is_active);
CREATE INDEX IF NOT EXISTS idx_chats_last_message_id ON chats(last_message_id);

CREATE INDEX IF NOT EXISTS idx_messages_chat_created ON messages(chat_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_sender_created ON messages(sender_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_chat_unread ON messages(chat_id, created_at DESC) WHERE is_read = FALSE;
CREATE INDEX IF NOT EXISTS idx_messages_reply_to ON messages(reply_to_message_id);

CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications(user_id, created_at DESC) WHERE is_read = FALSE;
CREATE INDEX IF NOT EXISTS idx_notifications_type_created ON notifications(type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_subscriptions_user_status ON subscriptions(user_id, status, end_date DESC);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status_end_date ON subscriptions(status, end_date DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_subscriptions_vk_subscription_id ON subscriptions(vk_subscription_id) WHERE vk_subscription_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_reports_reporter_created ON reports(reporter_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reports_target_created ON reports(target_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reports_status_created ON reports(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reports_reason_created ON reports(reason, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reports_chat_id ON reports(chat_id);
CREATE INDEX IF NOT EXISTS idx_reports_message_id ON reports(message_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_blacklist_user_blocked ON blacklist(user_id, blocked_user_id);
CREATE INDEX IF NOT EXISTS idx_blacklist_user_created ON blacklist(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_blacklist_blocked_created ON blacklist(blocked_user_id, created_at DESC);
