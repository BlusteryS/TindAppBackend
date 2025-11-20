package com.tindapp.repository.postgres;

import com.tindapp.model.BlackListItem;
import com.tindapp.repository.BlackListRepository;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class PostgresBlackListRepository extends AbstractPostgresRepository implements BlackListRepository {

    public PostgresBlackListRepository(PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS blacklist (
                id TEXT PRIMARY KEY,
                data JSONB NOT NULL
            )
            """);
    }

    @Override
    public BlackListItem save(BlackListItem entity) {
        if (entity == null) {
            throw new IllegalArgumentException("BlackListItem is null");
        }
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }
        JsonObject payload = toJson(entity);
        execute(
            "INSERT INTO blacklist (id, data) VALUES ($1, $2::jsonb) " +
                "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            Tuple.of(entity.getId(), payload)
        );
        return entity;
    }

    @Override
    public Optional<BlackListItem> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        RowSet<Row> rows = execute("SELECT data FROM blacklist WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapRow(rows.iterator().next(), BlackListItem.class));
    }

    @Override
    public List<BlackListItem> findAll() {
        RowSet<Row> rows = execute("SELECT data FROM blacklist");
        List<BlackListItem> result = new ArrayList<>();
        for (Row row : rows) {
            BlackListItem item = mapRow(row, BlackListItem.class);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public List<BlackListItem> findAll(int page, int limit) {
        List<BlackListItem> all = findAll();
        int start = (page - 1) * limit;
        int end = Math.min(start + limit, all.size());
        if (start >= all.size()) {
            return new ArrayList<>();
        }
        return all.subList(start, end);
    }

    @Override
    public List<BlackListItem> findByUserId(Long userId) {
        return findAll().stream()
            .filter(item -> userId.equals(item.getUserId()))
            .sorted(java.util.Comparator.comparing(
                BlackListItem::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())
            ).reversed())
            .collect(Collectors.toList());
    }

    @Override
    public List<BlackListItem> findByUserId(Long userId, int page, int limit) {
        List<BlackListItem> items = findByUserId(userId);
        int start = (page - 1) * limit;
        int end = Math.min(start + limit, items.size());
        if (start >= items.size()) {
            return new ArrayList<>();
        }
        return items.subList(start, end);
    }

    @Override
    public Optional<BlackListItem> findByUserIdAndBlockedUserId(Long userId, Long blockedUserId) {
        return findAll().stream()
            .filter(item -> userId.equals(item.getUserId()) && blockedUserId.equals(item.getBlockedUserId()))
            .findFirst();
    }

    @Override
    public List<BlackListItem> findByBlockedUserId(Long blockedUserId) {
        return findAll().stream()
            .filter(item -> blockedUserId.equals(item.getBlockedUserId()))
            .sorted(java.util.Comparator.comparing(
                BlackListItem::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())
            ).reversed())
            .collect(Collectors.toList());
    }

    @Override
    public boolean isBlocked(Long userId, Long blockedUserId) {
        return findByUserIdAndBlockedUserId(userId, blockedUserId).isPresent();
    }

    @Override
    public void unblockUser(Long userId, Long blockedUserId) {
        deleteByUserIdAndBlockedUserId(userId, blockedUserId);
    }

    @Override
    public long countByUserId(Long userId) {
        return findByUserId(userId).size();
    }

    @Override
    public long countByBlockedUserId(Long blockedUserId) {
        return findByBlockedUserId(blockedUserId).size();
    }

    @Override
    public void deleteByUserIdAndBlockedUserId(Long userId, Long blockedUserId) {
        findByUserIdAndBlockedUserId(userId, blockedUserId)
            .ifPresent(item -> deleteById(item.getId()));
    }

    @Override
    public void deleteById(String id) {
        execute("DELETE FROM blacklist WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(String id) {
        RowSet<Row> rows = execute("SELECT 1 FROM blacklist WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM blacklist");
        Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
    }
}
