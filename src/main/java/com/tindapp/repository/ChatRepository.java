package com.tindapp.repository;

import com.tindapp.model.Chat;

import java.util.List;
import java.util.Optional;

public interface ChatRepository extends Repository<Chat, String> {

    List<Chat> findByParticipantId(Long userId);

    List<Chat> findByParticipantId(Long userId, int page, int limit);

    Optional<Chat> findActiveAnonymousChat(Long userId);

    List<Chat> findActiveChats();

    void updateLastMessage(String chatId, String messageId);

    void updateUnreadCount(String chatId, Integer count);

    void markChatAsInactive(String chatId);

    boolean isParticipant(String chatId, Long userId);

    List<Chat> findByType(Chat.ChatType type);

    Optional<Chat> findByParticipants(Long user1Id, Long user2Id, Chat.ChatType type);
}
