package com.tindapp.repository;

import com.tindapp.model.Chat;
import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;

public interface ChatRepository extends Repository<Chat, String> {

    Future<List<Chat>> findByParticipantId(Long userId, int page, int limit);

    Future<List<Chat>> findByParticipantIdAndActive(Long userId, boolean isActive);

    Future<Optional<Chat>> findActiveAnonymousChat(Long userId);

    Future<Void> updateLastMessage(String chatId, String messageId);

    Future<Void> updateUnreadCount(String chatId, Integer count);

    Future<Void> markChatAsInactive(String chatId);

    Future<Boolean> isParticipant(String chatId, Long userId);

    Future<Optional<Chat>> findByParticipants(Long user1Id, Long user2Id, Chat.ChatType type);

    Future<List<Chat>> findByParticipants(Long user1Id, Long user2Id, boolean isActive, Chat.ChatClosureReason closureReason);

    Future<Boolean> existsActiveBetweenParticipants(Long user1Id, Long user2Id);

    Future<Long> countByParticipantId(Long userId);
}
