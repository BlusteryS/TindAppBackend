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
    public BlackListItem save(final BlackListItem blackListItem) {
        if (blackListItem.getId() == null) {
            blackListItem.setId(String.valueOf(idGenerator.getAndIncrement()));
        }
        blackListItems.put(blackListItem.getId(), blackListItem);
        return blackListItem;
    }

    @Override
    public Optional<BlackListItem> findById(final String id) {
        return Optional.ofNullable(blackListItems.get(id));
    }

    @Override
    public List<BlackListItem> findAll() {
        return new ArrayList<>(blackListItems.values());
    }

    @Override
    public List<BlackListItem> findAll(final int page, final int limit) {
        final List<BlackListItem> allItems = findAll().stream()
            .sorted((b1, b2) -> b2.getCreatedAt().compareTo(b1.getCreatedAt()))
            .collect(Collectors.toList());

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, allItems.size());

        if (start >= allItems.size()) {
            return new ArrayList<>();
        }

        return allItems.subList(start, end);
    }

    @Override
    public List<BlackListItem> findByUserId(final Long userId) {
        return blackListItems.values().stream()
            .filter(item -> userId.equals(item.getUserId()))
            .sorted((b1, b2) -> b2.getCreatedAt().compareTo(b1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public List<BlackListItem> findByUserId(final Long userId, final int page, final int limit) {
        final List<BlackListItem> userItems = findByUserId(userId);

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, userItems.size());

        if (start >= userItems.size()) {
            return new ArrayList<>();
        }

        return userItems.subList(start, end);
    }

    @Override
    public Optional<BlackListItem> findByUserIdAndBlockedUserId(final Long userId, final Long blockedUserId) {
        return blackListItems.values().stream()
            .filter(item -> userId.equals(item.getUserId()) &&
                blockedUserId.equals(item.getBlockedUserId()))
            .findFirst();
    }

    @Override
    public List<BlackListItem> findByBlockedUserId(final Long blockedUserId) {
        return blackListItems.values().stream()
            .filter(item -> blockedUserId.equals(item.getBlockedUserId()))
            .sorted((b1, b2) -> b2.getCreatedAt().compareTo(b1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public boolean isBlocked(final Long userId, final Long blockedUserId) {
        return findByUserIdAndBlockedUserId(userId, blockedUserId).isPresent();
    }

    @Override
    public void unblockUser(final Long userId, final Long blockedUserId) {
        blackListItems.entrySet().removeIf(entry -> {
            final BlackListItem item = entry.getValue();
            return userId.equals(item.getUserId()) &&
                blockedUserId.equals(item.getBlockedUserId());
        });
    }

    @Override
    public long countByUserId(final Long userId) {
        return blackListItems.values().stream()
            .filter(item -> userId.equals(item.getUserId()))
            .count();
    }

    @Override
    public long countByBlockedUserId(final Long blockedUserId) {
        return blackListItems.values().stream()
            .filter(item -> blockedUserId.equals(item.getBlockedUserId()))
            .count();
    }

    @Override
    public void deleteByUserIdAndBlockedUserId(final Long userId, final Long blockedUserId) {
        unblockUser(userId, blockedUserId);
    }

    @Override
    public void deleteById(final String id) {
        blackListItems.remove(id);
    }

    @Override
    public boolean existsById(final String id) {
        return blackListItems.containsKey(id);
    }

    @Override
    public long count() {
        return blackListItems.size();
    }
}
