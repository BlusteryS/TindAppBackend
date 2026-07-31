package com.tindapp.repository;

import com.tindapp.model.Message;
import io.vertx.core.Future;

import java.util.List;

public interface MessageRepository extends Repository<Message, String> {

    Future<List<Message>> findByChatId(String chatId, int page, int limit);

    Future<Void> markAsRead(String messageId);

    Future<Void> markMessagesAsRead(String chatId, Long readerId, List<String> messageIds);

    Future<Long> countUnreadMessagesByChatId(String chatId);

    Future<Long> countMessagesByChatId(String chatId);

    Future<List<Message>> findRecentByChatId(String chatId, int limit);
}
