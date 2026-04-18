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
    public List<Message> findAll(final int page, final int limit) {
        final List<Message> allMessages = new ArrayList<>(messages.values()).stream()
            .sorted(this::compareByCreatedAtDesc)
            .collect(Collectors.toList());
        return paginate(allMessages, page, limit);
    }

    private List<Message> getChatMessagesAscending(final String chatId) {
        return messages.values().stream()
            .filter(message -> chatId.equals(message.getChatId()))
            .sorted(this::compareByCreatedAtAsc)
            .collect(Collectors.toList());
    }

    @Override
    public List<Message> findByChatId(final String chatId, final int page, final int limit) {
        final List<Message> chatMessages = getChatMessagesAscending(chatId).stream()
            .sorted(this::compareByCreatedAtDesc)
            .collect(Collectors.toList());
        return paginate(chatMessages, page, limit);
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

        return getChatMessagesAscending(chatId).stream()
            .sorted(this::compareByCreatedAtDesc)
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

    private List<Message> paginate(final List<Message> messagesPage, final int page, final int limit) {
        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, messagesPage.size());
        if (start >= messagesPage.size()) {
            return new ArrayList<>();
        }
        return messagesPage.subList(start, end);
    }

    private int compareByCreatedAtAsc(final Message first, final Message second) {
        final LocalDateTime firstDate = DateTimeUtils.parseFromIso(first.getCreatedAt());
        final LocalDateTime secondDate = DateTimeUtils.parseFromIso(second.getCreatedAt());
        if (firstDate == null && secondDate == null) {
            return 0;
        }
        if (firstDate == null) {
            return 1;
        }
        if (secondDate == null) {
            return -1;
        }
        return firstDate.compareTo(secondDate);
    }

    private int compareByCreatedAtDesc(final Message first, final Message second) {
        return compareByCreatedAtAsc(second, first);
    }
}
