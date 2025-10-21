package com.tindapp.repository;

import com.tindapp.model.BlackListItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryBlackListRepository implements BlackListRepository {

    private final Map<String, BlackListItem> blackListItems = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public BlackListItem save(BlackListItem blackListItem) {
        if (blackListItem.getId() == null) {
            blackListItem.setId(String.valueOf(idGenerator.getAndIncrement()));
        }
        blackListItems.put(blackListItem.getId(), blackListItem);
        return blackListItem;
    }

    @Override
    public Optional<BlackListItem> findById(String id) {
        return Optional.ofNullable(blackListItems.get(id));
    }

    @Override
    public List<BlackListItem> findAll() {
        return new ArrayList<>(blackListItems.values());
    }

    @Override
    public List<BlackListItem> findAll(int page, int limit) {
        List<BlackListItem> allItems = findAll().stream()
                .sorted((b1, b2) -> b2.getCreatedAt().compareTo(b1.getCreatedAt()))
                .collect(Collectors.toList());

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, allItems.size());

        if (start >= allItems.size()) {
            return new ArrayList<>();
        }

        return allItems.subList(start, end);
    }

    @Override
    public List<BlackListItem> findByUserId(Long userId) {
        return blackListItems.values().stream()
                .filter(item -> userId.equals(item.getUserId()))
                .sorted((b1, b2) -> b2.getCreatedAt().compareTo(b1.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    public List<BlackListItem> findByUserId(Long userId, int page, int limit) {
        List<BlackListItem> userItems = findByUserId(userId);

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, userItems.size());

        if (start >= userItems.size()) {
            return new ArrayList<>();
        }

        return userItems.subList(start, end);
    }

    @Override
    public Optional<BlackListItem> findByUserIdAndBlockedUserId(Long userId, Long blockedUserId) {
        return blackListItems.values().stream()
                .filter(item -> userId.equals(item.getUserId()) &&
                               blockedUserId.equals(item.getBlockedUserId()))
                .findFirst();
    }

    @Override
    public List<BlackListItem> findByBlockedUserId(Long blockedUserId) {
        return blackListItems.values().stream()
                .filter(item -> blockedUserId.equals(item.getBlockedUserId()))
                .sorted((b1, b2) -> b2.getCreatedAt().compareTo(b1.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean isBlocked(Long userId, Long blockedUserId) {
        return findByUserIdAndBlockedUserId(userId, blockedUserId).isPresent();
    }

    @Override
    public void unblockUser(Long userId, Long blockedUserId) {
        blackListItems.entrySet().removeIf(entry -> {
            BlackListItem item = entry.getValue();
            return userId.equals(item.getUserId()) &&
                   blockedUserId.equals(item.getBlockedUserId());
        });
    }

    @Override
    public long countByUserId(Long userId) {
        return blackListItems.values().stream()
                .filter(item -> userId.equals(item.getUserId()))
                .count();
    }

    @Override
    public long countByBlockedUserId(Long blockedUserId) {
        return blackListItems.values().stream()
                .filter(item -> blockedUserId.equals(item.getBlockedUserId()))
                .count();
    }

    @Override
    public void deleteByUserIdAndBlockedUserId(Long userId, Long blockedUserId) {
        unblockUser(userId, blockedUserId);
    }

    @Override
    public void deleteById(String id) {
        blackListItems.remove(id);
    }

    @Override
    public boolean existsById(String id) {
        return blackListItems.containsKey(id);
    }

    @Override
    public long count() {
        return blackListItems.size();
    }
}
