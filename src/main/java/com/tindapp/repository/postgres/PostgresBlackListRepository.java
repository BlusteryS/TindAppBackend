package com.tindapp.repository.postgres;

import com.tindapp.model.BlackListItem;
import com.tindapp.repository.BlackListRepository;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    public BlackListItem save(final BlackListItem entity) {
        if (entity == null) {
            throw new IllegalArgumentException("BlackListItem is null");
        }
        if (entity.getId() == null || entity.getId().isBlank()) {
            entity.setId(UUID.randomUUID().toString());
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }

        execute("""
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
        ));
        return entity;
    }

    @Override
    public Optional<BlackListItem> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return firstRow(
            "SELECT " + BLACKLIST_COLUMNS + " FROM blacklist WHERE id = $1 LIMIT 1",
            Tuple.of(id)
        ).map(this::mapItem);
    }

    public List<BlackListItem> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryItems(
            "SELECT " + BLACKLIST_COLUMNS + " FROM blacklist ORDER BY created_at DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit))
        );
    }

    @Override
    public List<BlackListItem> findByUserId(final Long userId, final int page, final int limit) {
        if (userId == null) {
            return List.of();
        }
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryItems(
            "SELECT " + BLACKLIST_COLUMNS + " FROM blacklist WHERE user_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3",
            Tuple.of(userId, safeLimit, offset(page, safeLimit))
        );
    }

    @Override
    public Optional<BlackListItem> findByUserIdAndBlockedUserId(final Long userId, final Long blockedUserId) {
        if (userId == null || blockedUserId == null) {
            return Optional.empty();
        }
        return firstRow(
            "SELECT " + BLACKLIST_COLUMNS + " FROM blacklist WHERE user_id = $1 AND blocked_user_id = $2 LIMIT 1",
            Tuple.of(userId, blockedUserId)
        ).map(this::mapItem);
    }

    @Override
    public boolean isBlocked(final Long userId, final Long blockedUserId) {
        return exists(
            "SELECT 1 FROM blacklist WHERE user_id = $1 AND blocked_user_id = $2 LIMIT 1",
            Tuple.of(userId, blockedUserId)
        );
    }

    @Override
    public boolean existsByBlockedUserId(final Long blockedUserId) {
        if (blockedUserId == null) {
            return false;
        }
        return exists(
            "SELECT 1 FROM blacklist WHERE blocked_user_id = $1 LIMIT 1",
            Tuple.of(blockedUserId)
        );
    }

    @Override
    public void unblockUser(final Long userId, final Long blockedUserId) {
        deleteByUserIdAndBlockedUserId(userId, blockedUserId);
    }

    @Override
    public long countByUserId(final Long userId) {
        return countRows("SELECT COUNT(*) AS cnt FROM blacklist WHERE user_id = $1", Tuple.of(userId));
    }

    @Override
    public long countByBlockedUserId(final Long blockedUserId) {
        return countRows("SELECT COUNT(*) AS cnt FROM blacklist WHERE blocked_user_id = $1", Tuple.of(blockedUserId));
    }

    @Override
    public void deleteByUserIdAndBlockedUserId(final Long userId, final Long blockedUserId) {
        execute("DELETE FROM blacklist WHERE user_id = $1 AND blocked_user_id = $2", Tuple.of(userId, blockedUserId));
    }

    @Override
    public void deleteByUserId(final Long userId) {
        execute("DELETE FROM blacklist WHERE user_id = $1", Tuple.of(userId));
    }

    @Override
    public void deleteById(final String id) {
        execute("DELETE FROM blacklist WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(final String id) {
        return exists("SELECT 1 FROM blacklist WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public long count() {
        return countRows("SELECT COUNT(*) AS cnt FROM blacklist");
    }

    private List<BlackListItem> queryItems(final String sql, final Tuple params) {
        final RowSet<Row> rows = execute(sql, params);
        final List<BlackListItem> items = new ArrayList<>();
        for (final Row row : rows) {
            final BlackListItem item = mapItem(row);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
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
