package com.tindapp.repository.postgres;

import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.JacksonUtils;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresUserRepository extends AbstractPostgresRepository implements UserRepository {

    public PostgresUserRepository(final PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS users (
                id BIGSERIAL PRIMARY KEY,
                vk_id BIGINT UNIQUE,
                age INT,
                birth_date DATE,
                first_name TEXT,
                last_name TEXT,
                avatar_url TEXT,
                country TEXT,
                city TEXT,
                is_verified BOOLEAN DEFAULT FALSE,
                was_verified BOOLEAN DEFAULT FALSE,
                is_online BOOLEAN DEFAULT FALSE,
                last_seen TIMESTAMPTZ,
                bio TEXT,
                gender TEXT,
                is_visible BOOLEAN DEFAULT TRUE,
                allow_messages BOOLEAN DEFAULT TRUE,
                subscription_is_active BOOLEAN DEFAULT FALSE,
                subscription_type TEXT,
                subscription_expires_at TIMESTAMPTZ,
                balance INT,
                settings JSONB,
                rewards JSONB,
                profile_cost INT,
                is_admin BOOLEAN DEFAULT FALSE,
                is_banned BOOLEAN DEFAULT FALSE,
                ban_reason TEXT,
                banned_at TIMESTAMPTZ,
                native_language TEXT,
                updated_at TIMESTAMPTZ DEFAULT NOW(),
                created_at TIMESTAMPTZ DEFAULT NOW()
            );
            """);
        execute("CREATE INDEX IF NOT EXISTS idx_users_city ON users(city)");
        execute("CREATE INDEX IF NOT EXISTS idx_users_gender_age ON users(gender, age)");
        execute("CREATE INDEX IF NOT EXISTS idx_users_is_verified ON users(is_verified)");
        execute("CREATE INDEX IF NOT EXISTS idx_users_is_visible ON users(is_visible)");
        execute("CREATE INDEX IF NOT EXISTS idx_users_allow_messages ON users(allow_messages)");
        execute("CREATE INDEX IF NOT EXISTS idx_users_is_online ON users(is_online)");
        execute("CREATE INDEX IF NOT EXISTS idx_users_subscription_active ON users(subscription_is_active)");
        execute("CREATE INDEX IF NOT EXISTS idx_users_updated_at ON users(updated_at DESC)");
    }

    @Override
    public User save(final User user) {
        if (user == null) {
            throw new IllegalArgumentException("User is null");
        }

        if (user.getCreatedAtDateTime() == null) {
            user.setCreatedAtDateTime(LocalDateTime.now());
        }
        user.setUpdatedAtDateTime(LocalDateTime.now());

        final boolean isNew = user.getId() == null;
        final String sql = """
            INSERT INTO users (
                vk_id, age, birth_date, first_name, last_name, avatar_url, country, city,
                is_verified, was_verified, is_online, last_seen, bio, gender, is_visible,
                allow_messages, subscription_is_active, subscription_type, subscription_expires_at,
                balance, settings, rewards, profile_cost, is_admin, is_banned, ban_reason, banned_at,
                native_language, updated_at, created_at
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16,
                    $17, $18, $19, $20, $21, $22, $23, $24, $25, $26, $27, $28, $29, $30)
            ON CONFLICT (vk_id) DO UPDATE SET
                age = EXCLUDED.age,
                birth_date = EXCLUDED.birth_date,
                first_name = EXCLUDED.first_name,
                last_name = EXCLUDED.last_name,
                avatar_url = EXCLUDED.avatar_url,
                country = EXCLUDED.country,
                city = EXCLUDED.city,
                is_verified = EXCLUDED.is_verified,
                was_verified = EXCLUDED.was_verified,
                is_online = EXCLUDED.is_online,
                last_seen = EXCLUDED.last_seen,
                bio = EXCLUDED.bio,
                gender = EXCLUDED.gender,
                is_visible = EXCLUDED.is_visible,
                allow_messages = EXCLUDED.allow_messages,
                subscription_is_active = EXCLUDED.subscription_is_active,
                subscription_type = EXCLUDED.subscription_type,
                subscription_expires_at = EXCLUDED.subscription_expires_at,
                balance = EXCLUDED.balance,
                settings = EXCLUDED.settings,
                rewards = EXCLUDED.rewards,
                profile_cost = EXCLUDED.profile_cost,
                is_admin = EXCLUDED.is_admin,
                is_banned = EXCLUDED.is_banned,
                ban_reason = EXCLUDED.ban_reason,
                banned_at = EXCLUDED.banned_at,
                native_language = EXCLUDED.native_language,
                updated_at = NOW()
            RETURNING id, created_at
            """;

        final Tuple params = Tuple.of(
            user.getVkId(),
            user.getAge(),
            user.getBirthDate(),
            user.getFirstName(),
            user.getLastName(),
            user.getAvatarUrl(),
            user.getCountry(),
            user.getCity(),
            user.getIsVerified(),
            user.getWasVerified(),
            user.getIsOnline(),
            toOffset(user.getLastSeenDateTime()),
            user.getBio(),
            user.getGender(),
            user.getIsVisible(),
            user.getSettings() != null ? user.getSettings().getAllowMessages() : Boolean.TRUE,
            user.getSubscription() != null && Boolean.TRUE.equals(user.getSubscription().getIsActive()),
            user.getSubscription() != null && user.getSubscription().getType() != null ? user.getSubscription().getType().name().toLowerCase() : null,
            user.getSubscription() != null ? toOffset(user.getSubscription().getExpiresAt()) : null,
            user.getBalance(),
            user.getSettings() != null ? toJson(user.getSettings()) : null,
            user.getRewards() != null ? toJson(user.getRewards()) : null,
            user.getProfileCost(),
            user.getIsAdmin(),
            user.getIsBanned(),
            user.getBanReason(),
            toOffset(user.getBannedAt()),
            user.getNativeLanguage(),
            toOffset(user.getUpdatedAtDateTime()),
            toOffset(user.getCreatedAtDateTime())
        );

        final RowSet<Row> rows = execute(sql, params);
        final Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        if (row != null) {
            final Long generatedId = row.getLong("id");
            if (user.getId() == null) {
                user.setId(generatedId);
            }
            user.setCreatedAtDateTime(row.getOffsetDateTime("created_at") != null ? row.getOffsetDateTime("created_at").toLocalDateTime() : user.getCreatedAtDateTime());
        }
        return user;
    }

    @Override
    public Optional<User> findById(final Long id) {
        if (id == null) {
            return Optional.empty();
        }
        final RowSet<Row> rows = execute("SELECT * FROM users WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        final Row row = rows.iterator().next();
        return Optional.ofNullable(mapUser(row));
    }

    @Override
    public Optional<User> findByVkId(final Long vkId) {
        if (vkId == null) {
            return Optional.empty();
        }
        final RowSet<Row> rows = execute("SELECT * FROM users WHERE vk_id = $1 LIMIT 1", Tuple.of(vkId));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        final Row row = rows.iterator().next();
        return Optional.ofNullable(mapUser(row));
    }

    @Override
    public List<User> findAll() {
        final RowSet<Row> rows = execute("SELECT * FROM users");
        return mapUsers(rows);
    }

    @Override
    public List<User> findAll(final int page, final int limit) {
        final int offset = Math.max(0, (page - 1) * limit);
        final RowSet<Row> rows = execute(
            "SELECT * FROM users ORDER BY updated_at DESC OFFSET $1 LIMIT $2",
            Tuple.of(offset, limit)
        );
        return mapUsers(rows);
    }

    @Override
    public List<User> findOnlineUsers() {
        final RowSet<Row> rows = execute("SELECT * FROM users WHERE is_online = TRUE");
        return mapUsers(rows);
    }

    @Override
    public List<User> findByGender(final User.Gender gender) {
        if (gender == null) {
            return findAll();
        }
        final RowSet<Row> rows = execute("SELECT * FROM users WHERE gender = $1", Tuple.of(gender.toString().toLowerCase()));
        return mapUsers(rows);
    }

    @Override
    public List<User> findByAgeRange(final Integer minAge, final Integer maxAge) {
        final String ageExpr = "COALESCE(age, CAST(date_part('year', age(birth_date)) AS INT))";
        final StringBuilder sql = new StringBuilder("SELECT * FROM users WHERE 1=1");
        final List<Object> params = new ArrayList<>();
        if (minAge != null) {
            sql.append(" AND ").append(ageExpr).append(" >= $").append(params.size() + 1);
            params.add(minAge);
        }
        if (maxAge != null) {
            sql.append(" AND ").append(ageExpr).append(" <= $").append(params.size() + 1);
            params.add(maxAge);
        }
        final RowSet<Row> rows = execute(sql.toString(), Tuple.tuple(params));
        return mapUsers(rows);
    }

    @Override
    public List<User> findByCity(final String city) {
        final RowSet<Row> rows = execute(
            "SELECT * FROM users WHERE city = $1",
            Tuple.of(city)
        );
        return mapUsers(rows);
    }

    @Override
    public List<User> findForMatching(final User.Gender gender, final Integer minAge, final Integer maxAge, final String city, final Boolean verifiedOnly) {
        return findForMatching(gender, minAge, maxAge, city, verifiedOnly, 1, 500);
    }

    @Override
    public List<User> findForMatching(final User.Gender gender, final Integer minAge, final Integer maxAge, final String city, final Boolean verifiedOnly, final int page, final int limit) {
        final String ageExpr = "COALESCE(age, CAST(date_part('year', age(birth_date)) AS INT))";
        final StringBuilder sql = new StringBuilder("SELECT * FROM users WHERE is_visible = TRUE AND allow_messages = TRUE");
        final List<Object> params = new ArrayList<>();

        if (city != null) {
            sql.append(" AND city = $").append(params.size() + 1);
            params.add(city);
        }
        if (gender != null) {
            sql.append(" AND gender = $").append(params.size() + 1);
            params.add(gender.toString().toLowerCase());
        }
        if (minAge != null) {
            sql
                .append(" AND (")
                .append(ageExpr)
                .append(" IS NULL OR ")
                .append(ageExpr)
                .append(" >= $")
                .append(params.size() + 1)
                .append(')');
            params.add(minAge);
        }
        if (maxAge != null) {
            sql
                .append(" AND (")
                .append(ageExpr)
                .append(" IS NULL OR ")
                .append(ageExpr)
                .append(" <= $")
                .append(params.size() + 1)
                .append(')');
            params.add(maxAge);
        }
        if (verifiedOnly != null && verifiedOnly) {
            sql.append(" AND is_verified = TRUE");
        }

        final int safeLimit = Math.min(Math.max(limit, 1), 500);
        final int offset = Math.max(0, (page - 1) * safeLimit);
        sql.append(" ORDER BY updated_at DESC, is_verified DESC OFFSET $").append(params.size() + 1).append(" LIMIT $").append(params.size() + 2);
        params.add(offset);
        params.add(safeLimit);
        final RowSet<Row> rows = execute(sql.toString(), Tuple.tuple(params));
        return mapUsers(rows);
    }

    @Override
    public void updateOnlineStatus(final Long userId, final boolean isOnline) {
        execute(
            "UPDATE users SET " +
                "is_online = $2, " +
                "last_seen = CASE WHEN $2 = false THEN NOW() ELSE last_seen END, " +
                "updated_at = NOW() " +
                "WHERE id = $1",
            Tuple.of(userId, isOnline));
    }

    @Override
    public void updateBalance(final Long userId, final Integer balance) {
        execute("UPDATE users SET data = jsonb_set(data, '{balance}', to_jsonb($2::int), true), updated_at = NOW() WHERE id = $1", Tuple.of(userId, balance));
    }

    @Override
    public void deleteById(final Long id) {
        execute("DELETE FROM users WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(final Long id) {
        final RowSet<Row> rows = execute("SELECT 1 FROM users WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        final RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM users");
        final Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
    }

    private List<User> mapUsers(final RowSet<Row> rows) {
        final List<User> result = new ArrayList<>();
        for (final Row row : rows) {
            final User user = mapUser(row);
            if (user != null) {
                result.add(user);
            }
        }
        return result;
    }

    private OffsetDateTime toOffset(final LocalDateTime time) {
        return time != null ? time.atOffset(ZoneOffset.UTC) : null;
    }

    private User mapUser(final Row row) {
        if (row == null) {
            return null;
        }
        final User user = new User();
        user.setId(row.getLong("id"));
        user.setVkId(row.getLong("vk_id"));
        user.setAge((Integer) row.getValue("age"));
        user.setBirthDate(row.getLocalDate("birth_date"));
        user.setFirstName(row.getString("first_name"));
        user.setLastName(row.getString("last_name"));
        user.setAvatarUrl(row.getString("avatar_url"));
        user.setCountry(row.getString("country"));
        user.setCity(row.getString("city"));
        user.setIsVerified(row.getBoolean("is_verified"));
        user.setWasVerified(row.getBoolean("was_verified"));
        user.setOnline(Boolean.TRUE.equals(row.getBoolean("is_online")));
        user.setLastSeenDateTime(row.getLocalDateTime("last_seen"));
        user.setBio(row.getString("bio"));
        user.setGender(row.getString("gender"));
        user.setIsVisible(row.getBoolean("is_visible"));

        final User.UserSubscription subscription = new User.UserSubscription();
        subscription.setIsActive(row.getBoolean("subscription_is_active"));
        final String subType = row.getString("subscription_type");
        if (subType != null) {
            try {
                subscription.setType(User.SubscriptionType.valueOf(subType.toUpperCase()));
            } catch (final Exception ignored) {
            }
        }
        subscription.setExpiresAt(row.getLocalDateTime("subscription_expires_at"));
        user.setSubscription(subscription);

        user.setBalance(row.getInteger("balance"));

        final JsonObject settingsJson = row.getJsonObject("settings");
        if (settingsJson != null) {
            final User.UserSettings settings = JacksonUtils.fromJson(settingsJson, User.UserSettings.class);
            user.setSettings(settings);
        }

        final JsonObject rewardsJson = row.getJsonObject("rewards");
        if (rewardsJson != null) {
            final User.UserRewards rewards = JacksonUtils.fromJson(rewardsJson, User.UserRewards.class);
            user.setRewards(rewards);
        }

        user.setProfileCost(row.getInteger("profile_cost"));
        user.setIsAdmin(row.getBoolean("is_admin"));
        user.setIsBanned(row.getBoolean("is_banned"));
        user.setBanReason(row.getString("ban_reason"));
        user.setBannedAt(row.getLocalDateTime("banned_at"));
        user.setNativeLanguage(row.getString("native_language"));
        user.setUpdatedAtDateTime(row.getOffsetDateTime("updated_at") != null ? row.getOffsetDateTime("updated_at").toLocalDateTime() : null);
        user.setCreatedAtDateTime(row.getOffsetDateTime("created_at") != null ? row.getOffsetDateTime("created_at").toLocalDateTime() : null);
        return user;
    }
}
