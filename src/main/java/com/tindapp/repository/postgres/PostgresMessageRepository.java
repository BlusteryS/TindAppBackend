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

    public PostgresMessageRepository(PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                data JSONB NOT NULL
            )
            """);
    }

    @Override
    public Message save(Message message) {
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

        JsonObject payload = toJson(message);
        execute(
            "INSERT INTO messages (id, data) VALUES ($1, $2::jsonb) " +
                "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            Tuple.of(message.getId(), payload)
        );
        return message;
    }

    @Override
    public Optional<Message> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        RowSet<Row> rows = execute("SELECT data FROM messages WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapRow(rows.iterator().next(), Message.class));
    }

    @Override
    public List<Message> findAll() {
        RowSet<Row> rows = execute("SELECT data FROM messages");
        List<Message> result = new ArrayList<>();
        for (Row row : rows) {
            Message message = mapRow(row, Message.class);
            if (message != null) {
                result.add(message);
            }
        }
        return result;
    }

    @Override
    public List<Message> findAll(int page, int limit) {
        List<Message> allMessages = findAll().stream()
            .sorted((m1, m2) -> {
                LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .collect(Collectors.toList());

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, allMessages.size());
        if (start >= allMessages.size()) {
            return new ArrayList<>();
        }
        return allMessages.subList(start, end);
    }

    @Override
    public List<Message> findByChatId(String chatId) {
        return findAll().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .sorted(Comparator.comparing(m -> DateTimeUtils.parseFromIso(m.getCreatedAt()), Comparator.nullsLast(Comparator.naturalOrder())))
            .collect(Collectors.toList());
    }

    @Override
    public List<Message> findByChatId(String chatId, int page, int limit) {
        List<Message> chatMessages = findByChatId(chatId).stream()
            .sorted((m1, m2) -> {
                LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .collect(Collectors.toList());

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, chatMessages.size());
        if (start >= chatMessages.size()) {
            return new ArrayList<>();
        }
        return chatMessages.subList(start, end);
    }

    @Override
    public List<Message> findBySenderId(Long senderId) {
        return findAll().stream()
            .filter(message -> senderId.equals(message.getSenderId()))
            .sorted(Comparator.comparing(m -> DateTimeUtils.parseFromIso(m.getCreatedAt()), Comparator.nullsLast(Comparator.naturalOrder())))
            .collect(Collectors.toList());
    }

    @Override
    public List<Message> findUnreadMessagesByChatId(String chatId) {
        return findAll().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .filter(message -> Boolean.FALSE.equals(message.getIsRead()))
            .sorted(Comparator.comparing(m -> DateTimeUtils.parseFromIso(m.getCreatedAt()), Comparator.nullsLast(Comparator.naturalOrder())))
            .collect(Collectors.toList());
    }

    @Override
    public void markAsRead(String messageId) {
        findById(messageId).ifPresent(message -> {
            message.markAsRead();
            save(message);
        });
    }

    @Override
    public void markMessagesAsRead(String chatId, List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        messageIds.forEach(this::markAsRead);
    }

    @Override
    public long countUnreadMessagesByChatId(String chatId) {
        return findAll().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .filter(message -> Boolean.FALSE.equals(message.getIsRead()))
            .count();
    }

    @Override
    public long countMessagesByChatId(String chatId) {
        return findAll().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .count();
    }

    @Override
    public List<Message> findRecentByChatId(String chatId, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        return findByChatId(chatId).stream()
            .sorted((m1, m2) -> {
                LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .limit(limit)
            .collect(Collectors.toList());
    }

    @Override
    public void deleteById(String id) {
        execute("DELETE FROM messages WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(String id) {
        RowSet<Row> rows = execute("SELECT 1 FROM messages WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM messages");
        Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
    }
}
