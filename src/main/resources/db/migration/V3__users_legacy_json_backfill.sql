DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'users'
          AND column_name = 'data'
    ) THEN
        UPDATE users
        SET vk_id = COALESCE(NULLIF(data->>'vk_id', '')::BIGINT, NULLIF(data->>'vkId', '')::BIGINT, vk_id),
            age = COALESCE(NULLIF(data->>'age', '')::INT, age),
            birth_date = COALESCE(NULLIF(data->>'birth_date', '')::DATE, NULLIF(data->>'birthDate', '')::DATE, birth_date),
            first_name = COALESCE(NULLIF(data->>'first_name', ''), NULLIF(data->>'firstName', ''), NULLIF(first_name, ''), ''),
            last_name = COALESCE(NULLIF(data->>'last_name', ''), NULLIF(data->>'lastName', ''), NULLIF(last_name, ''), ''),
            avatar_url = COALESCE(NULLIF(data->>'avatar_url', ''), NULLIF(data->>'avatarUrl', ''), NULLIF(avatar_url, ''), ''),
            country = COALESCE(NULLIF(data->>'country', ''), NULLIF(country, ''), ''),
            city = COALESCE(NULLIF(data->>'city', ''), NULLIF(city, ''), ''),
            is_verified = COALESCE(NULLIF(data->>'is_verified', '')::BOOLEAN, NULLIF(data->>'isVerified', '')::BOOLEAN, is_verified, FALSE),
            was_verified = COALESCE(NULLIF(data->>'was_verified', '')::BOOLEAN, NULLIF(data->>'wasVerified', '')::BOOLEAN, was_verified, FALSE),
            is_online = COALESCE(NULLIF(data->>'is_online', '')::BOOLEAN, NULLIF(data->>'isOnline', '')::BOOLEAN, is_online, FALSE),
            last_seen = COALESCE(NULLIF(data->>'last_seen', '')::TIMESTAMPTZ, NULLIF(data->>'lastSeen', '')::TIMESTAMPTZ, last_seen),
            bio = COALESCE(NULLIF(data->>'bio', ''), NULLIF(bio, ''), ''),
            gender = LOWER(COALESCE(NULLIF(data->>'gender', ''), NULLIF(data->>'genderEnum', ''), gender, 'other')),
            is_visible = COALESCE(NULLIF(data->>'is_visible', '')::BOOLEAN, NULLIF(data->>'isVisible', '')::BOOLEAN, is_visible, TRUE),
            allow_messages = COALESCE(
                NULLIF(data->'settings'->>'allow_messages', '')::BOOLEAN,
                NULLIF(data->'settings'->>'allowMessages', '')::BOOLEAN,
                allow_messages,
                TRUE
            ),
            subscription_is_active = COALESCE(
                NULLIF(data->'subscription'->>'is_active', '')::BOOLEAN,
                NULLIF(data->'subscription'->>'isActive', '')::BOOLEAN,
                subscription_is_active,
                FALSE
            ),
            subscription_type = LOWER(COALESCE(NULLIF(data->'subscription'->>'type', ''), subscription_type)),
            subscription_expires_at = COALESCE(
                NULLIF(data->'subscription'->>'expires_at', '')::TIMESTAMPTZ,
                NULLIF(data->'subscription'->>'expiresAt', '')::TIMESTAMPTZ,
                subscription_expires_at
            ),
            balance = COALESCE(NULLIF(data->>'balance', '')::INT, balance, 0),
            settings = CASE
                WHEN data ? 'settings' THEN COALESCE(data->'settings', '{}'::jsonb)
                ELSE COALESCE(settings, '{}'::jsonb)
            END,
            rewards = CASE
                WHEN data ? 'rewards' THEN COALESCE(data->'rewards', '{}'::jsonb)
                ELSE COALESCE(rewards, '{}'::jsonb)
            END,
            profile_cost = COALESCE(NULLIF(data->>'profile_cost', '')::INT, NULLIF(data->>'profileCost', '')::INT, profile_cost, 0),
            is_admin = COALESCE(NULLIF(data->>'is_admin', '')::BOOLEAN, NULLIF(data->>'isAdmin', '')::BOOLEAN, is_admin, FALSE),
            is_banned = COALESCE(NULLIF(data->>'is_banned', '')::BOOLEAN, NULLIF(data->>'isBanned', '')::BOOLEAN, is_banned, FALSE),
            ban_reason = COALESCE(NULLIF(data->>'ban_reason', ''), NULLIF(data->>'banReason', ''), ban_reason),
            banned_at = COALESCE(NULLIF(data->>'banned_at', '')::TIMESTAMPTZ, NULLIF(data->>'bannedAt', '')::TIMESTAMPTZ, banned_at),
            native_language = COALESCE(NULLIF(data->>'native_language', ''), NULLIF(data->>'nativeLanguage', ''), NULLIF(native_language, ''), 'ru'),
            updated_at = COALESCE(NULLIF(data->>'updated_at', '')::TIMESTAMPTZ, NULLIF(data->>'updatedAt', '')::TIMESTAMPTZ, updated_at, NOW()),
            created_at = COALESCE(NULLIF(data->>'created_at', '')::TIMESTAMPTZ, NULLIF(data->>'createdAt', '')::TIMESTAMPTZ, created_at, NOW());

        ALTER TABLE users DROP COLUMN IF EXISTS data;
    END IF;
END $$;

UPDATE users
SET age = CAST(date_part('year', age(birth_date)) AS INT)
WHERE age IS NULL
  AND birth_date IS NOT NULL;

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
    gender = LOWER(COALESCE(gender, 'other')),
    is_visible = COALESCE(is_visible, TRUE),
    allow_messages = COALESCE(allow_messages, TRUE),
    subscription_is_active = COALESCE(subscription_is_active, FALSE),
    subscription_type = LOWER(subscription_type),
    balance = COALESCE(balance, 0),
    settings = COALESCE(settings, '{}'::jsonb),
    rewards = COALESCE(rewards, '{}'::jsonb),
    profile_cost = GREATEST(COALESCE(profile_cost, 0), 0),
    is_admin = COALESCE(is_admin, FALSE),
    is_banned = COALESCE(is_banned, FALSE),
    native_language = COALESCE(NULLIF(native_language, ''), 'ru'),
    updated_at = COALESCE(updated_at, NOW()),
    created_at = COALESCE(created_at, NOW());
