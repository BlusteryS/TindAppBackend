package com.tindapp.service;

import com.tindapp.model.BlackListItem;
import com.tindapp.repository.BlackListRepository;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.FutureUtils;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BlackListService {

    private static final Logger logger = LoggerFactory.getLogger(BlackListService.class);

    private final BlackListRepository blackListRepository;
    private final UserRepository userRepository;

    public BlackListService(final BlackListRepository blackListRepository, final UserRepository userRepository) {
        this.blackListRepository = blackListRepository;
        this.userRepository = userRepository;
    }

    public Future<BlackListItem> blockUser(final Long userId, final Long blockedUserId, final String reason) {
        if (userId == null || blockedUserId == null) {
            return FutureUtils.failed("User not found");
        }
        if (userId.equals(blockedUserId)) {
            return FutureUtils.failed("Cannot block yourself");
        }

        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> FutureUtils.requirePresent(userRepository.findById(blockedUserId), "Blocked user not found"))
            .compose(user -> isUserBlocked(userId, blockedUserId))
            .compose(blocked -> {
                if (blocked) {
                    return FutureUtils.failed("User is already blocked");
                }
                final BlackListItem blackListItem = new BlackListItem(UUID.randomUUID().toString(), userId, blockedUserId, reason);
                return blackListRepository.save(blackListItem);
            });
    }

    public Future<Void> unblockUser(final Long userId, final Long blockedUserId) {
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> FutureUtils.requirePresent(userRepository.findById(blockedUserId), "Blocked user not found"))
            .compose(user -> blackListRepository.findByUserIdAndBlockedUserId(userId, blockedUserId))
            .compose(blackListItem -> {
                if (blackListItem.isEmpty()) {
                    return FutureUtils.failed("User is not blocked");
                }
                return blackListRepository.deleteById(blackListItem.get().getId());
            });
    }

    public Future<List<BlackListItem>> getUserBlackList(final Long userId, final int page, final int limit) {
        return blackListRepository.findByUserId(userId, page, limit);
    }

    public Future<Boolean> isUserBlocked(final Long userId, final Long blockedUserId) {
        return blackListRepository.isBlocked(userId, blockedUserId);
    }

    public Future<Boolean> isUserBlockedByAnyone(final Long userId) {
        return blackListRepository.existsByBlockedUserId(userId);
    }

    public Future<Long> getBlockedUsersCount(final Long userId) {
        return blackListRepository.countByUserId(userId);
    }

    public Future<Long> getBlockersCount(final Long userId) {
        return blackListRepository.countByBlockedUserId(userId);
    }

    public Future<Optional<BlackListItem>> getBlockInfo(final Long userId, final Long blockedUserId) {
        return blackListRepository.findByUserIdAndBlockedUserId(userId, blockedUserId);
    }

    public Future<Void> deleteAllUserBlocks(final Long userId) {
        return blackListRepository.deleteByUserId(userId);
    }

    public Future<Boolean> canUsersInteract(final Long userId1, final Long userId2) {
        return isUserBlocked(userId1, userId2)
            .compose(blocked -> blocked ? Future.succeededFuture(false) : isUserBlocked(userId2, userId1).map(otherBlocked -> !otherBlocked));
    }

    public Future<List<Long>> filterBlockedUsers(final Long userId, final List<Long> userIds) {
        return FutureUtils.sequentialMap(userIds, targetUserId ->
                canUsersInteract(userId, targetUserId).map(canInteract -> canInteract ? targetUserId : null))
            .map(result -> result.stream().filter(java.util.Objects::nonNull).toList());
    }

    public Future<Boolean> shouldHideUser(final Long viewerId, final Long targetUserId) {
        return canUsersInteract(viewerId, targetUserId).map(canInteract -> !canInteract);
    }

    public Future<Void> processUserReport(final Long reporterId, final Long targetId, final int reportCount) {
        if (reportCount < 5) {
            return Future.succeededFuture();
        }
        return blockUser(1L, targetId, "Automatic block due to multiple reports")
            .map(item -> {
                logger.info("User {} automatically blocked due to {} reports", targetId, reportCount);
                return (Void) null;
            });
    }
}
