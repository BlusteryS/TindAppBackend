package com.tindapp.repository.postgres;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.JacksonUtils;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class PostgresUserRepository extends AbstractPostgresRepository implements UserRepository {

    private static final int MAX_LIMIT = 500;

    public PostgresUserRepository(final PgPool client) {
        super(client);
    }

    @Override
    public Future<User> save(final User user) {
        if (user == null) {
            return Future.failedFuture(new IllegalArgumentException("User is null"));
        }

        if (user.getCreatedAtDateTime() == null) {
            user.setCreatedAtDateTime(LocalDateTime.now());
        }
        user.setUpdatedAtDateTime(LocalDateTime.now());
        if (user.getBalance() == null) {
            user.setBalance(0);
        }
        if (user.getProfileCost() == null) {
            user.setProfileCost(0);
        }

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

        return execute(sql, params).map(rows -> {
            final Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
            if (row != null) {
                final Long generatedId = row.getLong("id");
                if (user.getId() == null) {
                    user.setId(generatedId);
                }
                if (row.getOffsetDateTime("created_at") != null) {
                    user.setCreatedAtDateTime(row.getOffsetDateTime("created_at").toLocalDateTime());
                }
            }
            return user;
        });
    }

    @Override
    public Future<Optional<User>> findById(final Long id) {
        if (id == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional("SELECT * FROM users WHERE id = $1 LIMIT 1", Tuple.of(id), this::mapUser);
    }

    @Override
    public Future<Optional<User>> findByVkId(final Long vkId) {
        if (vkId == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional("SELECT * FROM users WHERE vk_id = $1 LIMIT 1", Tuple.of(vkId), this::mapUser);
    }

    @Override
    public Future<List<User>> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryList(
            "SELECT * FROM users ORDER BY updated_at DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit)),
            this::mapUser
        );
    }

    @Override
    public Future<Long> countOnlineUsers() {
        return markStaleOnlineUsersOffline(AppConfig.ONLINE_STATUS_TTL)
            .compose(ignored -> countRows("SELECT COUNT(*) AS cnt FROM users WHERE is_online = TRUE"));
    }

    @Override
    public Future<List<User>> findForMatching(final Long viewerId, final User.Gender gender, final Integer minAge, final Integer maxAge,
                                              final String city, final Boolean verifiedOnly, final int page, final int limit) {
        final List<Object> params = new ArrayList<>();
        final StringBuilder sql = new StringBuilder("SELECT * FROM users");
        appendMatchingFilters(sql, params, viewerId, gender, minAge, maxAge, city, verifiedOnly);

        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        sql.append(" ORDER BY is_online DESC, last_seen DESC NULLS LAST, is_verified DESC, updated_at DESC, id DESC");
        sql.append(" LIMIT $").append(params.size() + 1).append(" OFFSET $").append(params.size() + 2);
        params.add(safeLimit);
        params.add(offset(page, safeLimit));

        return markStaleOnlineUsersOffline(AppConfig.ONLINE_STATUS_TTL)
            .compose(ignored -> queryList(sql.toString(), Tuple.tuple(params), this::mapUser));
    }

    @Override
    public Future<Long> countForMatching(final Long viewerId, final User.Gender gender, final Integer minAge, final Integer maxAge,
                                         final String city, final Boolean verifiedOnly) {
        final List<Object> params = new ArrayList<>();
        final StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt FROM users");
        appendMatchingFilters(sql, params, viewerId, gender, minAge, maxAge, city, verifiedOnly);
        return countRows(sql.toString(), Tuple.tuple(params));
    }

    @Override
    public Future<Void> updateOnlineStatus(final Long userId, final boolean isOnline) {
        return execute(
            """
                UPDATE users
                SET is_online = $2,
                    online_refreshed_at = CASE WHEN $2 = true THEN NOW() ELSE NULL END,
                    last_seen = CASE WHEN $2 = false THEN NOW() ELSE last_seen END,
                    updated_at = NOW()
                WHERE id = $1
                """,
            Tuple.of(userId, isOnline)
        ).mapEmpty();
    }

    @Override
    public Future<Void> refreshOnlineUsers(final Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Future.succeededFuture();
        }

        final Long[] ids = userIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toArray(Long[]::new);
        if (ids.length == 0) {
            return Future.succeededFuture();
        }

        return execute(
            "UPDATE users SET is_online = TRUE, online_refreshed_at = NOW() WHERE id = ANY($1)",
            Tuple.of(ids)
        ).mapEmpty();
    }

    @Override
    public Future<Void> markStaleOnlineUsersOffline(final Duration ttl) {
        final long ttlMillis = Math.max(1, ttl.toMillis());
        return execute(
            """
                UPDATE users
                SET is_online = FALSE,
                    online_refreshed_at = NULL,
                    last_seen = NOW(),
                    updated_at = NOW()
                WHERE is_online = TRUE
                  AND (
                    online_refreshed_at IS NULL
                    OR online_refreshed_at < NOW() - ($1::bigint * INTERVAL '1 millisecond')
                  )
                """,
            Tuple.of(ttlMillis)
        ).mapEmpty();
    }

    @Override
    public Future<Void> markAllOffline() {
        return execute(
            "UPDATE users SET is_online = FALSE, online_refreshed_at = NULL, last_seen = NOW(), updated_at = NOW() WHERE is_online = TRUE",
            Tuple.tuple()
        ).mapEmpty();
    }

    @Override
    public Future<Void> updateBalance(final Long userId, final Integer balance) {
        return execute("UPDATE users SET balance = $2, updated_at = NOW() WHERE id = $1", Tuple.of(userId, balance)).mapEmpty();
    }

    @Override
    public Future<Void> deleteById(final Long id) {
        return execute("DELETE FROM users WHERE id = $1", Tuple.of(id)).mapEmpty();
    }

    @Override
    public Future<Boolean> existsById(final Long id) {
        if (id == null) {
            return Future.succeededFuture(false);
        }
        return exists("SELECT 1 FROM users WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public Future<Long> count() {
        return countRows("SELECT COUNT(*) as cnt FROM users");
    }

    private void appendMatchingFilters(final StringBuilder sql, final List<Object> params, final Long viewerId, final User.Gender gender,
                                       final Integer minAge, final Integer maxAge, final String city, final Boolean verifiedOnly) {
        sql.append(" WHERE is_visible = TRUE AND allow_messages = TRUE AND is_banned = FALSE");

        if (viewerId != null) {
            sql.append(" AND id <> $").append(params.size() + 1);
            params.add(viewerId);
        }
        if (city != null && !city.isBlank()) {
            sql.append(" AND city = $").append(params.size() + 1);
            params.add(city);
        }
        if (gender != null) {
            sql.append(" AND gender = $").append(params.size() + 1);
            params.add(gender.toString().toLowerCase());
        }
        if (minAge != null) {
            sql.append(" AND (age IS NULL OR age >= $").append(params.size() + 1).append(')');
            params.add(minAge);
        }
        if (maxAge != null) {
            sql.append(" AND (age IS NULL OR age <= $").append(params.size() + 1).append(')');
            params.add(maxAge);
        }
        if (Boolean.TRUE.equals(verifiedOnly)) {
            sql.append(" AND is_verified = TRUE");
        }
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
        user.setLastSeenDateTime(row.getOffsetDateTime("last_seen") != null ? row.getOffsetDateTime("last_seen").toLocalDateTime() : null);
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
            user.setSettings(JacksonUtils.fromJson(settingsJson, User.UserSettings.class));
        }

        final JsonObject rewardsJson = row.getJsonObject("rewards");
        if (rewardsJson != null) {
            user.setRewards(JacksonUtils.fromJson(rewardsJson, User.UserRewards.class));
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
