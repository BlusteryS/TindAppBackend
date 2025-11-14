package com.tindapp.repository;

import com.tindapp.model.Message;

import java.util.List;

public interface MessageRepository extends Repository<Message, String> {

    List<Message> findByChatId(String chatId);

    List<Message> findByChatId(String chatId, int page, int limit);

    List<Message> findBySenderId(Long senderId);

    List<Message> findUnreadMessagesByChatId(String chatId);

    void markAsRead(String messageId);

    void markMessagesAsRead(String chatId, List<String> messageIds);

    long countUnreadMessagesByChatId(String chatId);

    long countMessagesByChatId(String chatId);

    List<Message> findRecentByChatId(String chatId, int limit);
}
