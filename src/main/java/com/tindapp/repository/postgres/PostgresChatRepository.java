package com.tindapp.repository.postgres;

import com.tindapp.model.Chat;
import com.tindapp.repository.ChatRepository;
import com.tindapp.util.DateTimeUtils;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class PostgresChatRepository extends AbstractPostgresRepository implements ChatRepository {

    public PostgresChatRepository(final PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS chats (
                id TEXT PRIMARY KEY,
                data JSONB NOT NULL
            )
            """);
    }

    @Override
    public Chat save(final Chat chat) {
        if (chat == null) {
            throw new IllegalArgumentException("Chat is null");
        }
        final JsonObject payload = toJson(chat);
        execute(
            "INSERT INTO chats (id, data) VALUES ($1, $2::jsonb) " +
                "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            Tuple.of(chat.getId(), payload)
        );
        return chat;
    }

    @Override
    public Optional<Chat> findById(final String id) {
        if (id == null) {
            return Optional.empty();
        }
        final RowSet<Row> rows = execute("SELECT data FROM chats WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapRow(rows.iterator().next(), Chat.class));
    }

    @Override
    public List<Chat> findAll() {
        final RowSet<Row> rows = execute("SELECT data FROM chats");
        final List<Chat> result = new ArrayList<>();
        for (final Row row : rows) {
            final Chat chat = mapRow(row, Chat.class);
            if (chat != null) {
                result.add(chat);
            }
        }
        return result;
    }

    @Override
    public List<Chat> findAll(final int page, final int limit) {
        final List<Chat> allChats = findAll().stream()
            .sorted((c1, c2) -> {
                final LocalDateTime date1 = DateTimeUtils.parseFromIso(c1.getUpdatedAt());
                final LocalDateTime date2 = DateTimeUtils.parseFromIso(c2.getUpdatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .collect(Collectors.toList());

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, allChats.size());
        if (start >= allChats.size()) {
            return new ArrayList<>();
        }
        return allChats.subList(start, end);
    }

    @Override
    public List<Chat> findByParticipantId(final Long userId) {
        return findAll().stream()
            .filter(chat -> chat.hasParticipant(userId))
            .sorted((c1, c2) -> {
                final LocalDateTime date1 = DateTimeUtils.parseFromIso(c1.getUpdatedAt());
                final LocalDateTime date2 = DateTimeUtils.parseFromIso(c2.getUpdatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<Chat> findByParticipantId(final Long userId, final int page, final int limit) {
        final List<Chat> chats = findByParticipantId(userId);
        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, chats.size());
        if (start >= chats.size()) {
            return new ArrayList<>();
        }
        return chats.subList(start, end);
    }

    @Override
    public Optional<Chat> findActiveAnonymousChat(final Long userId) {
        return findAll().stream()
            .filter(chat -> chat.getType() == Chat.ChatType.ANONYMOUS)
            .filter(chat -> Boolean.TRUE.equals(chat.getIsActive()))
            .filter(chat -> chat.hasParticipant(userId))
            .findFirst();
    }

    @Override
    public List<Chat> findActiveChats() {
        return findAll().stream()
            .filter(chat -> Boolean.TRUE.equals(chat.getIsActive()))
            .collect(Collectors.toList());
    }

    @Override
    public void updateLastMessage(final String chatId, final String messageId) {
        findById(chatId).ifPresent(chat -> {
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
            save(chat);
        });
    }

    @Override
    public void updateUnreadCount(final String chatId, final Integer count) {
        findById(chatId).ifPresent(chat -> {
            chat.setUnreadCount(count);
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
            save(chat);
        });
    }

    @Override
    public void markChatAsInactive(final String chatId) {
        findById(chatId).ifPresent(chat -> {
            chat.setIsActive(false);
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
            save(chat);
        });
    }

    @Override
    public boolean isParticipant(final String chatId, final Long userId) {
        return findById(chatId)
            .map(chat -> chat.hasParticipant(userId))
            .orElse(false);
    }

    @Override
    public List<Chat> findByType(final Chat.ChatType type) {
        return findAll().stream()
            .filter(chat -> chat.getType() == type)
            .collect(Collectors.toList());
    }

    @Override
    public Optional<Chat> findByParticipants(final Long user1Id, final Long user2Id, final Chat.ChatType type) {
        return findAll().stream()
            .filter(chat -> chat.getType() == type)
            .filter(chat -> Boolean.TRUE.equals(chat.getIsActive()))
            .filter(chat -> (chat.getUser1Id().equals(user1Id) && chat.getUser2Id().equals(user2Id)) ||
                (chat.getUser1Id().equals(user2Id) && chat.getUser2Id().equals(user1Id)))
            .findFirst();
    }

    @Override
    public void deleteById(final String id) {
        execute("DELETE FROM chats WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(final String id) {
        final RowSet<Row> rows = execute("SELECT 1 FROM chats WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        final RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM chats");
        final Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
    }
}
