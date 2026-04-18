package com.tindapp.repository;

import com.tindapp.model.BlackListItem;

import java.util.List;
import java.util.Optional;

public interface BlackListRepository extends Repository<BlackListItem, String> {

    List<BlackListItem> findByUserId(Long userId, int page, int limit);

    Optional<BlackListItem> findByUserIdAndBlockedUserId(Long userId, Long blockedUserId);

    boolean isBlocked(Long userId, Long blockedUserId);

    boolean existsByBlockedUserId(Long blockedUserId);

    void unblockUser(Long userId, Long blockedUserId);

    long countByUserId(Long userId);

    long countByBlockedUserId(Long blockedUserId);

    void deleteByUserIdAndBlockedUserId(Long userId, Long blockedUserId);

    void deleteByUserId(Long userId);
}
