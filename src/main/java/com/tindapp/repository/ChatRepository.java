package com.tindapp.repository;

import com.tindapp.model.Chat;

import java.util.List;
import java.util.Optional;

public interface ChatRepository extends Repository<Chat, String> {

    List<Chat> findByParticipantId(Long userId, int page, int limit);

    List<Chat> findByParticipantIdAndActive(Long userId, boolean isActive);

    Optional<Chat> findActiveAnonymousChat(Long userId);

    void updateLastMessage(String chatId, String messageId);

    void updateUnreadCount(String chatId, Integer count);

    void markChatAsInactive(String chatId);

    boolean isParticipant(String chatId, Long userId);

    Optional<Chat> findByParticipants(Long user1Id, Long user2Id, Chat.ChatType type);

    List<Chat> findByParticipants(Long user1Id, Long user2Id, boolean isActive, Chat.ChatClosureReason closureReason);

    boolean existsActiveBetweenParticipants(Long user1Id, Long user2Id);

    long countByParticipantId(Long userId);

    long countActiveByType(Chat.ChatType type);
}
