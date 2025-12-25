package com.tindapp.repository;

import com.tindapp.model.Message;
import com.tindapp.util.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryMessageRepository implements MessageRepository {

    private final Map<String, Message> messages = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public Message save(final Message message) {
        if (message.getId() == null) {
            message.setId(String.valueOf(idGenerator.getAndIncrement()));
        }
        message.setUpdatedAt(DateTimeUtils.nowAsIso());
        messages.put(message.getId(), message);
        return message;
    }

    @Override
    public Optional<Message> findById(final String id) {
        return Optional.ofNullable(messages.get(id));
    }

    @Override
    public List<Message> findAll() {
        return new ArrayList<>(messages.values());
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
        return messages.values().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .sorted((m1, m2) -> {
                final LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                final LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date1.compareTo(date2);
            })
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
        return messages.values().stream()
            .filter(message -> senderId.equals(message.getSenderId()))
            .sorted((m1, m2) -> {
                final LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                final LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date1.compareTo(date2);
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<Message> findUnreadMessagesByChatId(final String chatId) {
        return messages.values().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .filter(message -> Boolean.FALSE.equals(message.getIsRead()))
            .sorted((m1, m2) -> {
                final LocalDateTime date1 = DateTimeUtils.parseFromIso(m1.getCreatedAt());
                final LocalDateTime date2 = DateTimeUtils.parseFromIso(m2.getCreatedAt());
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date1.compareTo(date2);
            })
            .collect(Collectors.toList());
    }

    @Override
    public void markAsRead(final String messageId) {
        final Message message = messages.get(messageId);
        if (message != null) {
            message.markAsRead();
        }
    }

    @Override
    public void markMessagesAsRead(final String chatId, final List<String> messageIds) {
        messageIds.forEach(this::markAsRead);
    }

    @Override
    public long countUnreadMessagesByChatId(final String chatId) {
        return messages.values().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .filter(message -> Boolean.FALSE.equals(message.getIsRead()))
            .count();
    }

    @Override
    public long countMessagesByChatId(final String chatId) {
        return messages.values().stream()
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
        messages.remove(id);
    }

    @Override
    public boolean existsById(final String id) {
        return messages.containsKey(id);
    }

    @Override
    public long count() {
        return messages.size();
    }
}
