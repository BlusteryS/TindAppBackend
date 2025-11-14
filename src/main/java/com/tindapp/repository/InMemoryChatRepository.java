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
    public Chat save(Chat chat) {
        chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        chats.put(chat.getId(), chat);
        return chat;
    }

    @Override
    public Optional<Chat> findById(String id) {
        return Optional.ofNullable(chats.get(id));
    }

    @Override
    public List<Chat> findAll() {
        return new ArrayList<>(chats.values());
    }

    @Override
    public List<Chat> findAll(int page, int limit) {
        List<Chat> allChats = findAll().stream()
                .sorted((c1, c2) -> {
                    LocalDateTime date1 = DateTimeUtils.parseFromIso(c1.getUpdatedAt());
                    LocalDateTime date2 = DateTimeUtils.parseFromIso(c2.getUpdatedAt());
                    if (date1 == null && date2 == null) return 0;
                    if (date1 == null) return 1;
                    if (date2 == null) return -1;
                    return date2.compareTo(date1);
                })
                .collect(Collectors.toList());

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, allChats.size());

        if (start >= allChats.size()) {
            return new ArrayList<>();
        }

        return allChats.subList(start, end);
    }

    @Override
    public List<Chat> findByParticipantId(Long userId) {
        return chats.values().stream()
                .filter(chat -> chat.hasParticipant(userId))
                .sorted((c1, c2) -> {
                    LocalDateTime date1 = DateTimeUtils.parseFromIso(c1.getUpdatedAt());
                    LocalDateTime date2 = DateTimeUtils.parseFromIso(c2.getUpdatedAt());
                    if (date1 == null && date2 == null) return 0;
                    if (date1 == null) return 1;
                    if (date2 == null) return -1;
                    return date2.compareTo(date1);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Chat> findByParticipantId(Long userId, int page, int limit) {
        List<Chat> userChats = findByParticipantId(userId);

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, userChats.size());

        if (start >= userChats.size()) {
            return new ArrayList<>();
        }

        return userChats.subList(start, end);
    }

    @Override
    public Optional<Chat> findActiveAnonymousChat(Long userId) {
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
    public List<Chat> findByType(Chat.ChatType type) {
        return chats.values().stream()
                .filter(chat -> chat.getType() == type)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Chat> findByParticipants(Long user1Id, Long user2Id, Chat.ChatType type) {
        return chats.values().stream()
                .filter(chat -> chat.getType() == type)
                .filter(chat -> Boolean.TRUE.equals(chat.getIsActive()))
                .filter(chat -> (chat.getUser1Id().equals(user1Id) && chat.getUser2Id().equals(user2Id)) ||
                        (chat.getUser1Id().equals(user2Id) && chat.getUser2Id().equals(user1Id)))
                .findFirst();
    }

    @Override
    public void updateLastMessage(String chatId, String messageId) {
        Chat chat = chats.get(chatId);
        if (chat != null) {
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        }
    }

    @Override
    public void updateUnreadCount(String chatId, Integer count) {
        Chat chat = chats.get(chatId);
        if (chat != null) {
            chat.setUnreadCount(count);
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        }
    }

    @Override
    public void markChatAsInactive(String chatId) {
        Chat chat = chats.get(chatId);
        if (chat != null) {
            chat.setIsActive(false);
            chat.setUpdatedAt(DateTimeUtils.nowAsIso());
        }
    }

    @Override
    public boolean isParticipant(String chatId, Long userId) {
        Chat chat = chats.get(chatId);
        return chat != null && chat.hasParticipant(userId);
    }

    @Override
    public void deleteById(String id) {
        chats.remove(id);
    }

    @Override
    public boolean existsById(String id) {
        return chats.containsKey(id);
    }

    @Override
    public long count() {
        return chats.size();
    }
}
