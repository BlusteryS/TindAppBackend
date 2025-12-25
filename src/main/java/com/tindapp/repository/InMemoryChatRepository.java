package com.tindapp.repository;

import com.tindapp.model.Chat;
import com.tindapp.util.DateTimeUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryChatRepository implements ChatRepository {

    private final Map<String, Chat> chats = new ConcurrentHashMap<>();

    @Override
    public Chat save(final Chat chat) {
        chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        chats.put(chat.getId(), chat);
        return chat;
    }

    @Override
    public Optional<Chat> findById(final String id) {
        return Optional.ofNullable(chats.get(id));
    }

    @Override
    public List<Chat> findAll() {
        return new ArrayList<>(chats.values());
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
        return chats.values().stream()
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
        final List<Chat> userChats = findByParticipantId(userId);

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, userChats.size());

        if (start >= userChats.size()) {
            return new ArrayList<>();
        }

        return userChats.subList(start, end);
    }

    @Override
    public Optional<Chat> findActiveAnonymousChat(final Long userId) {
        return chats.values().stream()
            .filter(chat -> chat.getType() == Chat.ChatType.ANONYMOUS)
            .filter(chat -> Boolean.TRUE.equals(chat.getIsActive()))
            .filter(chat -> chat.hasParticipant(userId))
            .findFirst();
    }

    @Override
    public List<Chat> findActiveChats() {
        return chats.values().stream()
            .filter(chat -> Boolean.TRUE.equals(chat.getIsActive()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Chat> findByType(final Chat.ChatType type) {
        return chats.values().stream()
            .filter(chat -> chat.getType() == type)
            .collect(Collectors.toList());
    }

    @Override
    public Optional<Chat> findByParticipants(final Long user1Id, final Long user2Id, final Chat.ChatType type) {
        return chats.values().stream()
            .filter(chat -> chat.getType() == type)
            .filter(chat -> Boolean.TRUE.equals(chat.getIsActive()))
            .filter(chat -> (chat.getUser1Id().equals(user1Id) && chat.getUser2Id().equals(user2Id)) ||
                (chat.getUser1Id().equals(user2Id) && chat.getUser2Id().equals(user1Id)))
            .findFirst();
    }

    @Override
    public void updateLastMessage(final String chatId, final String messageId) {
        final Chat chat = chats.get(chatId);
        if (chat != null) {
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        }
    }

    @Override
    public void updateUnreadCount(final String chatId, final Integer count) {
        final Chat chat = chats.get(chatId);
        if (chat != null) {
            chat.setUnreadCount(count);
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        }
    }

    @Override
    public void markChatAsInactive(final String chatId) {
        final Chat chat = chats.get(chatId);
        if (chat != null) {
            chat.setIsActive(false);
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        }
    }

    @Override
    public boolean isParticipant(final String chatId, final Long userId) {
        final Chat chat = chats.get(chatId);
        return chat != null && chat.hasParticipant(userId);
    }

    @Override
    public void deleteById(final String id) {
        chats.remove(id);
    }

    @Override
    public boolean existsById(final String id) {
        return chats.containsKey(id);
    }

    @Override
    public long count() {
        return chats.size();
    }
}
