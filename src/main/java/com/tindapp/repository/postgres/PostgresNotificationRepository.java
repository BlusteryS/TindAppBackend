package com.tindapp.repository.postgres;

import com.tindapp.model.Notification;
import com.tindapp.repository.NotificationRepository;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class PostgresNotificationRepository extends AbstractPostgresRepository implements NotificationRepository {

    public PostgresNotificationRepository(PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS notifications (
                id TEXT PRIMARY KEY,
                data JSONB NOT NULL
            )
            """);
    }

    @Override
    public Notification save(Notification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("Notification is null");
        }
        if (notification.getId() == null) {
            notification.setId(UUID.randomUUID().toString());
        }
        JsonObject payload = toJson(notification);
        execute(
            "INSERT INTO notifications (id, data) VALUES ($1, $2::jsonb) " +
                "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            Tuple.of(notification.getId(), payload)
        );
        return notification;
    }

    @Override
    public Optional<Notification> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        RowSet<Row> rows = execute("SELECT data FROM notifications WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapRow(rows.iterator().next(), Notification.class));
    }

    @Override
    public List<Notification> findAll() {
        RowSet<Row> rows = execute("SELECT data FROM notifications");
        List<Notification> result = new ArrayList<>();
        for (Row row : rows) {
            Notification notification = mapRow(row, Notification.class);
            if (notification != null) {
                result.add(notification);
            }
        }
        return result;
    }

    @Override
    public List<Notification> findAll(int page, int limit) {
        List<Notification> all = findAll().stream()
            .sorted(Comparator.comparing(Notification::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .collect(Collectors.toList());
        int start = (page - 1) * limit;
        int end = Math.min(start + limit, all.size());
        if (start >= all.size()) {
            return new ArrayList<>();
        }
        return all.subList(start, end);
    }

    @Override
    public List<Notification> findByUserId(Long userId) {
        return findAll().stream()
            .filter(notification -> userId.equals(notification.getUserId()))
            .sorted(Comparator.comparing(Notification::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .collect(Collectors.toList());
    }

    @Override
    public List<Notification> findByUserId(Long userId, int page, int limit) {
        List<Notification> userNotifications = findByUserId(userId);
        int start = (page - 1) * limit;
        int end = Math.min(start + limit, userNotifications.size());
        if (start >= userNotifications.size()) {
            return new ArrayList<>();
        }
        return userNotifications.subList(start, end);
    }

    @Override
    public List<Notification> findUnreadByUserId(Long userId) {
        return findAll().stream()
            .filter(notification -> userId.equals(notification.getUserId()))
            .filter(notification -> Boolean.FALSE.equals(notification.getIsRead()))
            .sorted(Comparator.comparing(Notification::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .collect(Collectors.toList());
    }

    @Override
    public void markAsRead(String notificationId) {
        findById(notificationId).ifPresent(notification -> {
            notification.markAsRead();
            save(notification);
        });
    }

    @Override
    public void markAllAsReadByUserId(Long userId) {
        findByUserId(userId).forEach(notification -> {
            notification.markAsRead();
            save(notification);
        });
    }

    @Override
    public void markAsReadByIds(List<String> notificationIds) {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return;
        }
        notificationIds.forEach(this::markAsRead);
    }

    @Override
    public long countUnreadByUserId(Long userId) {
        return findAll().stream()
            .filter(notification -> userId.equals(notification.getUserId()))
            .filter(notification -> Boolean.FALSE.equals(notification.getIsRead()))
            .count();
    }

    @Override
    public List<Notification> findByType(Notification.NotificationType type) {
        return findAll().stream()
            .filter(notification -> type.equals(notification.getType()))
            .sorted(Comparator.comparing(Notification::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .collect(Collectors.toList());
    }

    @Override
    public void deleteByUserId(Long userId) {
        findByUserId(userId).forEach(notification -> deleteById(notification.getId()));
    }

    @Override
    public void deleteById(String id) {
        execute("DELETE FROM notifications WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(String id) {
        RowSet<Row> rows = execute("SELECT 1 FROM notifications WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM notifications");
        Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
    }
}
