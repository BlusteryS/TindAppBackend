package com.tindapp.repository.postgres;

import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.repository.ChatRepository;
import com.tindapp.util.DateTimeUtils;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgresChatRepository extends AbstractPostgresRepository implements ChatRepository {

    private static final int MAX_LIMIT = 100;
    private static final String CHAT_COLUMNS = """
        id, type, user1_id, user2_id, last_message, last_message_id, unread_count,
        settings, is_active, created_at, updated_at, closed_by_user_id, closure_reason, closed_at
        """;

    public PostgresChatRepository(final PgPool client) {
        super(client);
    }

    @Override
    public Future<Chat> save(final Chat chat) {
        if (chat == null) {
            return Future.failedFuture(new IllegalArgumentException("Chat is null"));
        }
        if (chat.getId() == null || chat.getId().isBlank()) {
            return Future.failedFuture(new IllegalArgumentException("Chat id is required"));
        }
        if (chat.getCreatedAt() == null) {
            chat.setCreatedAt(DateTimeUtils.nowAsIso());
        }
        chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        if (chat.getSettings() == null) {
            chat.setSettings(new Chat.ChatSettings());
        }
        if (chat.getUnreadCount() == null) {
            chat.setUnreadCount(0);
        }
        if (chat.getIsActive() == null) {
            chat.setIsActive(true);
        }

        final Long participantLowId = chat.getUser1Id() != null && chat.getUser2Id() != null
            ? Math.min(chat.getUser1Id(), chat.getUser2Id())
            : null;
        final Long participantHighId = chat.getUser1Id() != null && chat.getUser2Id() != null
            ? Math.max(chat.getUser1Id(), chat.getUser2Id())
            : null;
        final JsonObject settingsJson = toJson(chat.getSettings());
        final JsonObject lastMessageJson = chat.getLastMessage() != null ? toJson(chat.getLastMessage()) : null;

        return execute("""
            INSERT INTO chats (
                id, type, user1_id, user2_id, participant_low_id, participant_high_id,
                last_message, last_message_id, unread_count, settings, is_active,
                created_at, updated_at, closed_by_user_id, closure_reason, closed_at
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9, $10::jsonb, $11, $12, $13, $14, $15, $16)
            ON CONFLICT (id) DO UPDATE SET
                type = EXCLUDED.type,
                user1_id = EXCLUDED.user1_id,
                user2_id = EXCLUDED.user2_id,
                participant_low_id = EXCLUDED.participant_low_id,
                participant_high_id = EXCLUDED.participant_high_id,
                last_message = EXCLUDED.last_message,
                last_message_id = EXCLUDED.last_message_id,
                unread_count = EXCLUDED.unread_count,
                settings = EXCLUDED.settings,
                is_active = EXCLUDED.is_active,
                created_at = EXCLUDED.created_at,
                updated_at = EXCLUDED.updated_at,
                closed_by_user_id = EXCLUDED.closed_by_user_id,
                closure_reason = EXCLUDED.closure_reason,
                closed_at = EXCLUDED.closed_at
            """, Tuple.of(
            chat.getId(),
            chat.getType() != null ? chat.getType().name() : null,
            chat.getUser1Id(),
            chat.getUser2Id(),
            participantLowId,
            participantHighId,
            lastMessageJson,
            chat.getLastMessage() != null ? chat.getLastMessage().getId() : null,
            chat.getUnreadCount(),
            settingsJson,
            chat.getIsActive(),
            toOffset(chat.getCreatedAt()),
            toOffset(chat.getUpdatedAt()),
            chat.getClosedByUserId(),
            chat.getClosureReason() != null ? chat.getClosureReason().name() : null,
            toOffset(chat.getClosedAt())
        )).map(chat);
    }

    @Override
    public Future<Optional<Chat>> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional("SELECT " + CHAT_COLUMNS + " FROM chats WHERE id = $1 LIMIT 1", Tuple.of(id), this::mapChat);
    }

    @Override
    public Future<List<Chat>> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryList(
            "SELECT " + CHAT_COLUMNS + " FROM chats ORDER BY updated_at DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit)),
            this::mapChat
        );
    }

    @Override
    public Future<List<Chat>> findByParticipantIdAndActive(final Long userId, final boolean isActive) {
        if (userId == null) {
            return Future.succeededFuture(List.of());
        }
        return queryList(
            "SELECT " + CHAT_COLUMNS + " FROM chats WHERE (user1_id = $1 OR user2_id = $1) AND is_active = $2 ORDER BY updated_at DESC",
            Tuple.of(userId, isActive),
            this::mapChat
        );
    }

    @Override
    public Future<List<Chat>> findByParticipantId(final Long userId, final int page, final int limit) {
        if (userId == null) {
            return Future.succeededFuture(List.of());
        }
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryList(
            "SELECT " + CHAT_COLUMNS + " FROM chats WHERE user1_id = $1 OR user2_id = $1 ORDER BY updated_at DESC LIMIT $2 OFFSET $3",
            Tuple.of(userId, safeLimit, offset(page, safeLimit)),
            this::mapChat
        );
    }

    @Override
    public Future<Optional<Chat>> findActiveAnonymousChat(final Long userId) {
        if (userId == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional(
            "SELECT " + CHAT_COLUMNS + " FROM chats WHERE type = 'ANONYMOUS' AND is_active = TRUE AND (user1_id = $1 OR user2_id = $1) ORDER BY updated_at DESC LIMIT 1",
            Tuple.of(userId),
            this::mapChat
        );
    }

    @Override
    public Future<Void> updateLastMessage(final String chatId, final String messageId) {
        return findById(chatId).compose(chatOpt -> {
            if (chatOpt.isEmpty()) {
                return Future.succeededFuture();
            }
            final Chat chat = chatOpt.get();
            if (chat.getLastMessage() == null) {
                chat.setLastMessage(new Message());
            }
            chat.getLastMessage().setId(messageId);
            return save(chat).mapEmpty();
        });
    }

    @Override
    public Future<Void> updateUnreadCount(final String chatId, final Integer count) {
        return findById(chatId).compose(chatOpt -> {
            if (chatOpt.isEmpty()) {
                return Future.succeededFuture();
            }
            final Chat chat = chatOpt.get();
            chat.setUnreadCount(count != null ? count : 0);
            return save(chat).mapEmpty();
        });
    }

    @Override
    public Future<Void> markChatAsInactive(final String chatId) {
        return findById(chatId).compose(chatOpt -> {
            if (chatOpt.isEmpty()) {
                return Future.succeededFuture();
            }
            final Chat chat = chatOpt.get();
            chat.setIsActive(false);
            return save(chat).mapEmpty();
        });
    }

    @Override
    public Future<Boolean> isParticipant(final String chatId, final Long userId) {
        if (chatId == null || userId == null) {
            return Future.succeededFuture(false);
        }
        return exists(
            "SELECT 1 FROM chats WHERE id = $1 AND (user1_id = $2 OR user2_id = $2) LIMIT 1",
            Tuple.of(chatId, userId)
        );
    }

    @Override
    public Future<List<Chat>> findByParticipants(final Long user1Id, final Long user2Id, final boolean isActive,
                                                 final Chat.ChatClosureReason closureReason) {
        if (user1Id == null || user2Id == null) {
            return Future.succeededFuture(List.of());
        }
        final long participantLowId = Math.min(user1Id, user2Id);
        final long participantHighId = Math.max(user1Id, user2Id);
        final StringBuilder sql = new StringBuilder(
            "SELECT " + CHAT_COLUMNS + " FROM chats WHERE participant_low_id = $1 AND participant_high_id = $2 AND is_active = $3"
        );
        final List<Object> params = new ArrayList<>();
        params.add(participantLowId);
        params.add(participantHighId);
        params.add(isActive);
        if (closureReason != null) {
            sql.append(" AND closure_reason = $").append(params.size() + 1);
            params.add(closureReason.name());
        }
        sql.append(" ORDER BY updated_at DESC");
        return queryList(sql.toString(), Tuple.tuple(params), this::mapChat);
    }

    @Override
    public Future<Optional<Chat>> findByParticipants(final Long user1Id, final Long user2Id, final Chat.ChatType type) {
        if (user1Id == null || user2Id == null || type == null) {
            return Future.succeededFuture(Optional.empty());
        }
        final long participantLowId = Math.min(user1Id, user2Id);
        final long participantHighId = Math.max(user1Id, user2Id);
        return queryOptional(
            "SELECT " + CHAT_COLUMNS + " FROM chats WHERE participant_low_id = $1 AND participant_high_id = $2 AND type = $3 AND is_active = TRUE ORDER BY updated_at DESC LIMIT 1",
            Tuple.of(participantLowId, participantHighId, type.name()),
            this::mapChat
        );
    }

    @Override
    public Future<Boolean> existsActiveBetweenParticipants(final Long user1Id, final Long user2Id) {
        if (user1Id == null || user2Id == null) {
            return Future.succeededFuture(false);
        }
        final long participantLowId = Math.min(user1Id, user2Id);
        final long participantHighId = Math.max(user1Id, user2Id);
        return exists(
            "SELECT 1 FROM chats WHERE participant_low_id = $1 AND participant_high_id = $2 AND is_active = TRUE LIMIT 1",
            Tuple.of(participantLowId, participantHighId)
        );
    }

    @Override
    public Future<Long> countByParticipantId(final Long userId) {
        if (userId == null) {
            return Future.succeededFuture(0L);
        }
        return countRows(
            "SELECT COUNT(*) AS cnt FROM chats WHERE user1_id = $1 OR user2_id = $1",
            Tuple.of(userId)
        );
    }

    @Override
    public Future<Void> deleteById(final String id) {
        return execute("DELETE FROM chats WHERE id = $1", Tuple.of(id)).mapEmpty();
    }

    @Override
    public Future<Boolean> existsById(final String id) {
        if (id == null || id.isBlank()) {
            return Future.succeededFuture(false);
        }
        return exists("SELECT 1 FROM chats WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public Future<Long> count() {
        return countRows("SELECT COUNT(*) AS cnt FROM chats");
    }

    private Chat mapChat(final Row row) {
        if (row == null) {
            return null;
        }
        final Chat chat = new Chat();
        chat.setId(row.getString("id"));

        final String type = row.getString("type");
        if (type != null) {
            chat.setType(Chat.ChatType.valueOf(type));
        }

        chat.setUser1Id(row.getLong("user1_id"));
        chat.setUser2Id(row.getLong("user2_id"));
        chat.setLastMessage(fromJsonObject(row.getValue("last_message"), Message.class));
        chat.setUnreadCount(row.getInteger("unread_count"));

        Chat.ChatSettings settings = fromJsonObject(row.getValue("settings"), Chat.ChatSettings.class);
        if (settings == null) {
            settings = new Chat.ChatSettings();
        }
        chat.setSettings(settings);

        chat.setIsActive(row.getBoolean("is_active"));
        chat.setCreatedAt(toIso(row.getOffsetDateTime("created_at")));
        chat.setUpdatedAt(toIso(row.getOffsetDateTime("updated_at")));
        chat.setClosedByUserId(row.getLong("closed_by_user_id"));

        final String closureReason = row.getString("closure_reason");
        if (closureReason != null) {
            chat.setClosureReason(Chat.ChatClosureReason.valueOf(closureReason));
        }

        final OffsetDateTime closedAt = row.getOffsetDateTime("closed_at");
        chat.setClosedAt(toIso(closedAt));
        return chat;
    }
}
