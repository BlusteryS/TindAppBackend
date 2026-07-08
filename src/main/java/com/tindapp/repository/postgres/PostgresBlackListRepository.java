package com.tindapp.repository.postgres;

import com.tindapp.model.BlackListItem;
import com.tindapp.repository.BlackListRepository;
import io.vertx.core.Future;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PostgresBlackListRepository extends AbstractPostgresRepository implements BlackListRepository {

    private static final int MAX_LIMIT = 100;
    private static final String BLACKLIST_COLUMNS = "id, user_id, blocked_user_id, reason, created_at";

    public PostgresBlackListRepository(final PgPool client) {
        super(client);
    }

    @Override
    public Future<BlackListItem> save(final BlackListItem entity) {
        if (entity == null) {
            return Future.failedFuture(new IllegalArgumentException("BlackListItem is null"));
        }
        if (entity.getId() == null || entity.getId().isBlank()) {
            entity.setId(UUID.randomUUID().toString());
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }

        return execute("""
            INSERT INTO blacklist (id, user_id, blocked_user_id, reason, created_at)
            VALUES ($1, $2, $3, $4, $5)
            ON CONFLICT (id) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                blocked_user_id = EXCLUDED.blocked_user_id,
                reason = EXCLUDED.reason,
                created_at = EXCLUDED.created_at
            """, Tuple.of(
            entity.getId(),
            entity.getUserId(),
            entity.getBlockedUserId(),
            entity.getReason(),
            toOffset(entity.getCreatedAt())
        )).map(entity);
    }

    @Override
    public Future<Optional<BlackListItem>> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional(
            "SELECT " + BLACKLIST_COLUMNS + " FROM blacklist WHERE id = $1 LIMIT 1",
            Tuple.of(id),
            this::mapItem
        );
    }

    @Override
    public Future<List<BlackListItem>> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryList(
            "SELECT " + BLACKLIST_COLUMNS + " FROM blacklist ORDER BY created_at DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit)),
            this::mapItem
        );
    }

    @Override
    public Future<List<BlackListItem>> findByUserId(final Long userId, final int page, final int limit) {
        if (userId == null) {
            return Future.succeededFuture(List.of());
        }
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryList(
            "SELECT " + BLACKLIST_COLUMNS + " FROM blacklist WHERE user_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3",
            Tuple.of(userId, safeLimit, offset(page, safeLimit)),
            this::mapItem
        );
    }

    @Override
    public Future<Optional<BlackListItem>> findByUserIdAndBlockedUserId(final Long userId, final Long blockedUserId) {
        if (userId == null || blockedUserId == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional(
            "SELECT " + BLACKLIST_COLUMNS + " FROM blacklist WHERE user_id = $1 AND blocked_user_id = $2 LIMIT 1",
            Tuple.of(userId, blockedUserId),
            this::mapItem
        );
    }

    @Override
    public Future<Boolean> isBlocked(final Long userId, final Long blockedUserId) {
        if (userId == null || blockedUserId == null) {
            return Future.succeededFuture(false);
        }
        return exists(
            "SELECT 1 FROM blacklist WHERE user_id = $1 AND blocked_user_id = $2 LIMIT 1",
            Tuple.of(userId, blockedUserId)
        );
    }

    @Override
    public Future<Boolean> existsByBlockedUserId(final Long blockedUserId) {
        if (blockedUserId == null) {
            return Future.succeededFuture(false);
        }
        return exists(
            "SELECT 1 FROM blacklist WHERE blocked_user_id = $1 LIMIT 1",
            Tuple.of(blockedUserId)
        );
    }

    @Override
    public Future<Void> unblockUser(final Long userId, final Long blockedUserId) {
        return deleteByUserIdAndBlockedUserId(userId, blockedUserId);
    }

    @Override
    public Future<Long> countByUserId(final Long userId) {
        if (userId == null) {
            return Future.succeededFuture(0L);
        }
        return countRows("SELECT COUNT(*) AS cnt FROM blacklist WHERE user_id = $1", Tuple.of(userId));
    }

    @Override
    public Future<Long> countByBlockedUserId(final Long blockedUserId) {
        if (blockedUserId == null) {
            return Future.succeededFuture(0L);
        }
        return countRows("SELECT COUNT(*) AS cnt FROM blacklist WHERE blocked_user_id = $1", Tuple.of(blockedUserId));
    }

    @Override
    public Future<Void> deleteByUserIdAndBlockedUserId(final Long userId, final Long blockedUserId) {
        return execute("DELETE FROM blacklist WHERE user_id = $1 AND blocked_user_id = $2", Tuple.of(userId, blockedUserId))
            .mapEmpty();
    }

    @Override
    public Future<Void> deleteByUserId(final Long userId) {
        return execute("DELETE FROM blacklist WHERE user_id = $1", Tuple.of(userId)).mapEmpty();
    }

    @Override
    public Future<Void> deleteById(final String id) {
        return execute("DELETE FROM blacklist WHERE id = $1", Tuple.of(id)).mapEmpty();
    }

    @Override
    public Future<Boolean> existsById(final String id) {
        if (id == null || id.isBlank()) {
            return Future.succeededFuture(false);
        }
        return exists("SELECT 1 FROM blacklist WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public Future<Long> count() {
        return countRows("SELECT COUNT(*) AS cnt FROM blacklist");
    }

    private BlackListItem mapItem(final Row row) {
        if (row == null) {
            return null;
        }
        final BlackListItem item = new BlackListItem();
        item.setId(row.getString("id"));
        item.setUserId(row.getLong("user_id"));
        item.setBlockedUserId(row.getLong("blocked_user_id"));
        item.setReason(row.getString("reason"));

        final OffsetDateTime createdAt = row.getOffsetDateTime("created_at");
        item.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        return item;
    }
}
