package com.tindapp.repository;

import com.tindapp.model.BlackListItem;
import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;

public interface BlackListRepository extends Repository<BlackListItem, String> {

    Future<List<BlackListItem>> findByUserId(Long userId, int page, int limit);

    Future<Optional<BlackListItem>> findByUserIdAndBlockedUserId(Long userId, Long blockedUserId);

    Future<Boolean> isBlocked(Long userId, Long blockedUserId);

    Future<Boolean> existsByBlockedUserId(Long blockedUserId);

    Future<Void> unblockUser(Long userId, Long blockedUserId);

    Future<Long> countByUserId(Long userId);

    Future<Long> countByBlockedUserId(Long blockedUserId);

    Future<Void> deleteByUserIdAndBlockedUserId(Long userId, Long blockedUserId);

    Future<Void> deleteByUserId(Long userId);
}
