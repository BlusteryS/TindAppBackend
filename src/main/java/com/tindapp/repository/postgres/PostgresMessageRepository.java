package com.tindapp.repository.postgres;

import com.tindapp.model.Message;
import com.tindapp.repository.MessageRepository;
import com.tindapp.util.DateTimeUtils;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class PostgresMessageRepository extends AbstractPostgresRepository implements MessageRepository {

    public PostgresMessageRepository(final PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                data JSONB NOT NULL
            )
            """);
    }

    @Override
    public Message save(final Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message is null");
        }
        if (message.getId() == null) {
            message.setId(UUID.randomUUID().toString());
        }
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(DateTimeUtils.nowAsIso());
        }
        message.setUpdatedAt(DateTimeUtils.nowAsIso());

        final JsonObject payload = toJson(message);
        execute(
            "INSERT INTO messages (id, data) VALUES ($1, $2::jsonb) " +
                "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            Tuple.of(message.getId(), payload)
        );
        return message;
    }

    @Override
    public Optional<Message> findById(final String id) {
        if (id == null) {
            return Optional.empty();
        }
        final RowSet<Row> rows = execute("SELECT data FROM messages WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapRow(rows.iterator().next(), Message.class));
    }

    @Override
    public List<Message> findAll() {
        final RowSet<Row> rows = execute("SELECT data FROM messages");
        final List<Message> result = new ArrayList<>();
        for (final Row row : rows) {
            final Message message = mapRow(row, Message.class);
            if (message != null) {
                result.add(message);
            }
        }
        return result;
    }

    @Override
    public List<Message> findAll(final int page, final int limit) {
        final List<Message> allMessages = findAll().stream()
            .sorted((m1, m2) -> {
                final LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                final LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .collect(Collectors.toList());

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, allMessages.size());
        if (start >= allMessages.size()) {
            return new ArrayList<>();
        }
        return allMessages.subList(start, end);
    }

    @Override
    public List<Message> findByChatId(final String chatId) {
        return findAll().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .sorted(Comparator.comparing(m -> DateTimeUtils.parseFromIso(m.getCreatedAt()), Comparator.nullsLast(Comparator.naturalOrder())))
            .collect(Collectors.toList());
    }

    @Override
    public List<Message> findByChatId(final String chatId, final int page, final int limit) {
        final List<Message> chatMessages = findByChatId(chatId).stream()
            .sorted((m1, m2) -> {
                final LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                final LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .collect(Collectors.toList());

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, chatMessages.size());
        if (start >= chatMessages.size()) {
            return new ArrayList<>();
        }
        return chatMessages.subList(start, end);
    }

    @Override
    public List<Message> findBySenderId(final Long senderId) {
        return findAll().stream()
            .filter(message -> senderId.equals(message.getSenderId()))
            .sorted(Comparator.comparing(m -> DateTimeUtils.parseFromIso(m.getCreatedAt()), Comparator.nullsLast(Comparator.naturalOrder())))
            .collect(Collectors.toList());
    }

    @Override
    public List<Message> findUnreadMessagesByChatId(final String chatId) {
        return findAll().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .filter(message -> Boolean.FALSE.equals(message.getIsRead()))
            .sorted(Comparator.comparing(m -> DateTimeUtils.parseFromIso(m.getCreatedAt()), Comparator.nullsLast(Comparator.naturalOrder())))
            .collect(Collectors.toList());
    }

    @Override
    public void markAsRead(final String messageId) {
        findById(messageId).ifPresent(message -> {
            message.markAsRead();
            save(message);
        });
    }

    @Override
    public void markMessagesAsRead(final String chatId, final List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        messageIds.forEach(this::markAsRead);
    }

    @Override
    public long countUnreadMessagesByChatId(final String chatId) {
        return findAll().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .filter(message -> Boolean.FALSE.equals(message.getIsRead()))
            .count();
    }

    @Override
    public long countMessagesByChatId(final String chatId) {
        return findAll().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .count();
    }

    @Override
    public List<Message> findRecentByChatId(final String chatId, final int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        return findByChatId(chatId).stream()
            .sorted((m1, m2) -> {
                final LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                final LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .limit(limit)
            .collect(Collectors.toList());
    }

    @Override
    public void deleteById(final String id) {
        execute("DELETE FROM messages WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(final String id) {
        final RowSet<Row> rows = execute("SELECT 1 FROM messages WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        final RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM messages");
        final Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
    }
}
