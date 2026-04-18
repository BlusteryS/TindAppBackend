package com.tindapp.repository.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tindapp.model.Message;
import com.tindapp.repository.MessageRepository;
import com.tindapp.util.DateTimeUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PostgresMessageRepository extends AbstractPostgresRepository implements MessageRepository {

    private static final int MAX_LIMIT = 200;
    private static final TypeReference<List<Message.MessageAttachment>> ATTACHMENTS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Message.MessageTranslation>> TRANSLATIONS_TYPE = new TypeReference<>() {
    };
    private static final String MESSAGE_COLUMNS = """
        id, chat_id, sender_id, text, type, reply_to, reply_to_message_id, attachments,
        translations, is_read, is_edited, created_at, updated_at
        """;

    public PostgresMessageRepository(final PgPool client) {
        super(client);
    }

    @Override
    public Message save(final Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message is null");
        }
        if (message.getId() == null || message.getId().isBlank()) {
            message.setId(UUID.randomUUID().toString());
        }
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(DateTimeUtils.nowAsIso());
        }
        if (message.getText() == null) {
            message.setText("");
        }
        if (message.getType() == null) {
            message.setType(Message.MessageType.TEXT);
        }
        if (message.getIsRead() == null) {
            message.setIsRead(false);
        }
        if (message.getIsEdited() == null) {
            message.setIsEdited(false);
        }

        message.setUpdatedAt(DateTimeUtils.nowAsIso());

        final JsonObject replyToJson = message.getReplyTo() != null ? toJson(message.getReplyTo()) : null;
        final JsonArray attachmentsJson =
            message.getAttachments() != null ? new JsonArray(message.getAttachments().stream().map(this::toJson).toList()) : null;
        final JsonObject translationsJson =
            message.getTranslations() != null ? toJson(message.getTranslations()) : null;

        execute("""
            INSERT INTO messages (
                id, chat_id, sender_id, text, type, reply_to, reply_to_message_id, attachments,
                translations, is_read, is_edited, created_at, updated_at
            )
            VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7, $8::jsonb, $9::jsonb, $10, $11, $12, $13)
            ON CONFLICT (id) DO UPDATE SET
                chat_id = EXCLUDED.chat_id,
                sender_id = EXCLUDED.sender_id,
                text = EXCLUDED.text,
                type = EXCLUDED.type,
                reply_to = EXCLUDED.reply_to,
                reply_to_message_id = EXCLUDED.reply_to_message_id,
                attachments = EXCLUDED.attachments,
                translations = EXCLUDED.translations,
                is_read = EXCLUDED.is_read,
                is_edited = EXCLUDED.is_edited,
                created_at = EXCLUDED.created_at,
                updated_at = EXCLUDED.updated_at
            """, Tuple.of(
            message.getId(),
            message.getChatId(),
            message.getSenderId(),
            message.getText(),
            message.getType().name(),
            replyToJson,
            message.getReplyTo() != null ? message.getReplyTo().getMessageId() : null,
            attachmentsJson,
            translationsJson,
            message.getIsRead(),
            message.getIsEdited(),
            toOffset(message.getCreatedAt()),
            toOffset(message.getUpdatedAt())
        ));
        return message;
    }

    @Override
    public Optional<Message> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return firstRow("SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE id = $1 LIMIT 1", Tuple.of(id))
            .map(this::mapMessage);
    }

    public List<Message> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryMessages(
            "SELECT " + MESSAGE_COLUMNS + " FROM messages ORDER BY created_at DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit))
        );
    }

    public List<Message> findByChatId(final String chatId, final int page, final int limit) {
        if (chatId == null || chatId.isBlank()) {
            return List.of();
        }
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryMessages(
            "SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE chat_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3",
            Tuple.of(chatId, safeLimit, offset(page, safeLimit))
        );
    }

    public void markAsRead(final String messageId) {
        execute("UPDATE messages SET is_read = TRUE, updated_at = NOW() WHERE id = $1", Tuple.of(messageId));
    }

    @Override
    public void markMessagesAsRead(final String chatId, final List<String> messageIds) {
        if (chatId == null || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        final String[] ids = messageIds.toArray(String[]::new);
        execute(
            "UPDATE messages SET is_read = TRUE, updated_at = NOW() WHERE chat_id = $1 AND id = ANY($2)",
            Tuple.of(chatId, (Object) ids)
        );
    }

    @Override
    public long countUnreadMessagesByChatId(final String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return 0L;
        }
        return countRows(
            "SELECT COUNT(*) AS cnt FROM messages WHERE chat_id = $1 AND is_read = FALSE",
            Tuple.of(chatId)
        );
    }

    @Override
    public long countMessagesByChatId(final String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return 0L;
        }
        return countRows("SELECT COUNT(*) AS cnt FROM messages WHERE chat_id = $1", Tuple.of(chatId));
    }

    @Override
    public List<Message> findRecentByChatId(final String chatId, final int limit) {
        if (chatId == null || chatId.isBlank() || limit <= 0) {
            return List.of();
        }
        return queryMessages(
            "SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE chat_id = $1 ORDER BY created_at DESC LIMIT $2",
            Tuple.of(chatId, safeLimit(limit, MAX_LIMIT))
        );
    }

    @Override
    public void deleteById(final String id) {
        execute("DELETE FROM messages WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(final String id) {
        return exists("SELECT 1 FROM messages WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public long count() {
        return countRows("SELECT COUNT(*) AS cnt FROM messages");
    }

    private List<Message> queryMessages(final String sql, final Tuple params) {
        final RowSet<Row> rows = execute(sql, params);
        final List<Message> messages = new ArrayList<>();
        for (final Row row : rows) {
            final Message message = mapMessage(row);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    private Message mapMessage(final Row row) {
        if (row == null) {
            return null;
        }
        final Message message = new Message();
        message.setId(row.getString("id"));
        message.setChatId(row.getString("chat_id"));
        message.setSenderId(row.getLong("sender_id"));
        message.setText(row.getString("text"));

        final String type = row.getString("type");
        if (type != null) {
            message.setType(Message.MessageType.valueOf(type));
        }

        message.setReplyTo(fromJsonObject(row.getValue("reply_to"), Message.ReplyInfo.class));
        message.setAttachments(fromJsonValue(row.getValue("attachments"), ATTACHMENTS_TYPE));
        message.setTranslations(fromJsonValue(row.getValue("translations"), TRANSLATIONS_TYPE));
        message.setIsRead(row.getBoolean("is_read"));
        message.setIsEdited(row.getBoolean("is_edited"));
        message.setCreatedAt(toIso(row.getOffsetDateTime("created_at")));
        message.setUpdatedAt(toIso(row.getOffsetDateTime("updated_at")));
        return message;
    }
}
