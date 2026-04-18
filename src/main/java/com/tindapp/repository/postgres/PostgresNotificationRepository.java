package com.tindapp.repository.postgres;

import com.tindapp.model.Notification;
import com.tindapp.repository.NotificationRepository;
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

public class PostgresNotificationRepository extends AbstractPostgresRepository implements NotificationRepository {

    private static final int MAX_LIMIT = 100;
    private static final String NOTIFICATION_COLUMNS = "id, user_id, type, title, message, is_read, payload, created_at";

    public PostgresNotificationRepository(final PgPool client) {
        super(client);
    }

    @Override
    public Notification save(final Notification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("Notification is null");
        }
        if (notification.getId() == null || notification.getId().isBlank()) {
            notification.setId(UUID.randomUUID().toString());
        }
        if (notification.getCreatedAt() == null) {
            notification.setCreatedAt(LocalDateTime.now());
        }
        if (notification.getType() == null) {
            notification.setType(Notification.NotificationType.SYSTEM);
        }
        if (notification.getTitle() == null) {
            notification.setTitle("");
        }
        if (notification.getMessage() == null) {
            notification.setMessage("");
        }
        if (notification.getIsRead() == null) {
            notification.setIsRead(false);
        }

        final JsonObject payload = notification.getData() != null ? new JsonObject(notification.getData()) : null;
        execute("""
            INSERT INTO notifications (id, user_id, type, title, message, is_read, payload, created_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8)
            ON CONFLICT (id) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                type = EXCLUDED.type,
                title = EXCLUDED.title,
                message = EXCLUDED.message,
                is_read = EXCLUDED.is_read,
                payload = EXCLUDED.payload,
                created_at = EXCLUDED.created_at
            """, Tuple.of(
            notification.getId(),
            notification.getUserId(),
            notification.getType().name(),
            notification.getTitle(),
            notification.getMessage(),
            notification.getIsRead(),
            payload,
            toOffset(notification.getCreatedAt())
        ));
        return notification;
    }

    @Override
    public Optional<Notification> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return firstRow(
            "SELECT " + NOTIFICATION_COLUMNS + " FROM notifications WHERE id = $1 LIMIT 1",
            Tuple.of(id)
        ).map(this::mapNotification);
    }

    public List<Notification> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryNotifications(
            "SELECT " + NOTIFICATION_COLUMNS + " FROM notifications ORDER BY created_at DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit))
        );
    }

    @Override
    public List<Notification> findByUserId(final Long userId, final int page, final int limit) {
        if (userId == null) {
            return List.of();
        }
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryNotifications(
            "SELECT " + NOTIFICATION_COLUMNS + " FROM notifications WHERE user_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3",
            Tuple.of(userId, safeLimit, offset(page, safeLimit))
        );
    }

    @Override
    public void markAsRead(final String notificationId) {
        execute("UPDATE notifications SET is_read = TRUE WHERE id = $1", Tuple.of(notificationId));
    }

    @Override
    public void markAllAsReadByUserId(final Long userId) {
        execute("UPDATE notifications SET is_read = TRUE WHERE user_id = $1", Tuple.of(userId));
    }

    @Override
    public void markAsReadByIds(final List<String> notificationIds) {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return;
        }
        execute(
            "UPDATE notifications SET is_read = TRUE WHERE id = ANY($1)",
            Tuple.of(notificationIds.toArray(String[]::new))
        );
    }

    @Override
    public long countUnreadByUserId(final Long userId) {
        return countRows(
            "SELECT COUNT(*) AS cnt FROM notifications WHERE user_id = $1 AND is_read = FALSE",
            Tuple.of(userId)
        );
    }

    @Override
    public long countByUserId(final Long userId) {
        if (userId == null) {
            return 0L;
        }
        return countRows(
            "SELECT COUNT(*) AS cnt FROM notifications WHERE user_id = $1",
            Tuple.of(userId)
        );
    }

    @Override
    public void deleteByUserId(final Long userId) {
        execute("DELETE FROM notifications WHERE user_id = $1", Tuple.of(userId));
    }

    @Override
    public void deleteById(final String id) {
        execute("DELETE FROM notifications WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(final String id) {
        return exists("SELECT 1 FROM notifications WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public long count() {
        return countRows("SELECT COUNT(*) AS cnt FROM notifications");
    }

    private List<Notification> queryNotifications(final String sql, final Tuple params) {
        final RowSet<Row> rows = execute(sql, params);
        final List<Notification> notifications = new ArrayList<>();
        for (final Row row : rows) {
            final Notification notification = mapNotification(row);
            if (notification != null) {
                notifications.add(notification);
            }
        }
        return notifications;
    }

    private Notification mapNotification(final Row row) {
        if (row == null) {
            return null;
        }
        final Notification notification = new Notification();
        notification.setId(row.getString("id"));
        notification.setUserId(row.getLong("user_id"));

        final String type = row.getString("type");
        if (type != null) {
            notification.setType(Notification.NotificationType.valueOf(type));
        }

        notification.setTitle(row.getString("title"));
        notification.setMessage(row.getString("message"));
        notification.setIsRead(row.getBoolean("is_read"));

        final JsonObject payload = row.getJsonObject("payload");
        if (payload != null) {
            notification.setData(payload.getMap());
        }

        final java.time.OffsetDateTime createdAt = row.getOffsetDateTime("created_at");
        notification.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        return notification;
    }
}
