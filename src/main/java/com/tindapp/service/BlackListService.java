package com.tindapp.service;

import com.tindapp.model.BlackListItem;
import com.tindapp.repository.BlackListRepository;
import com.tindapp.repository.UserRepository;
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

    public BlackListItem blockUser(final Long userId, final Long blockedUserId, final String reason) {
        userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        userRepository.findById(blockedUserId)
            .orElseThrow(() -> new RuntimeException("Blocked user not found"));

        if (userId.equals(blockedUserId)) {
            throw new RuntimeException("Cannot block yourself");
        }

        if (isUserBlocked(userId, blockedUserId)) {
            throw new RuntimeException("User is already blocked");
        }

        final String blackListId = UUID.randomUUID().toString();
        final BlackListItem blackListItem = new BlackListItem(blackListId, userId, blockedUserId, reason);

        return blackListRepository.save(blackListItem);
    }

    public void unblockUser(final Long userId, final Long blockedUserId) {
        userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        userRepository.findById(blockedUserId)
            .orElseThrow(() -> new RuntimeException("Blocked user not found"));

        final Optional<BlackListItem> blackListItem = blackListRepository.findByUserIdAndBlockedUserId(userId, blockedUserId);
        if (blackListItem.isPresent()) {
            blackListRepository.deleteById(blackListItem.get().getId());
        } else {
            throw new RuntimeException("User is not blocked");
        }
    }

    public List<BlackListItem> getUserBlackList(final Long userId, final int page, final int limit) {
        return blackListRepository.findByUserId(userId, page, limit);
    }

    public List<BlackListItem> getUserBlackList(final Long userId) {
        return blackListRepository.findByUserId(userId);
    }

    public boolean isUserBlocked(final Long userId, final Long blockedUserId) {
        return blackListRepository.isBlocked(userId, blockedUserId);
    }

    public boolean isUserBlockedByAnyone(final Long userId) {
        final List<BlackListItem> blockers = blackListRepository.findByBlockedUserId(userId);
        return !blockers.isEmpty();
    }

    public List<BlackListItem> getWhoBlockedUser(final Long userId) {
        return blackListRepository.findByBlockedUserId(userId);
    }

    public long getBlockedUsersCount(final Long userId) {
        return blackListRepository.countByUserId(userId);
    }

    public long getBlockersCount(final Long userId) {
        return blackListRepository.countByBlockedUserId(userId);
    }

    public Optional<BlackListItem> getBlockInfo(final Long userId, final Long blockedUserId) {
        return blackListRepository.findByUserIdAndBlockedUserId(userId, blockedUserId);
    }

    public void deleteAllUserBlocks(final Long userId) {
        final List<BlackListItem> userBlocks = blackListRepository.findByUserId(userId);
        for (final BlackListItem item : userBlocks) {
            blackListRepository.deleteById(item.getId());
        }
    }

    public boolean canUsersInteract(final Long userId1, final Long userId2) {
        return !isUserBlocked(userId1, userId2) && !isUserBlocked(userId2, userId1);
    }

    public List<Long> filterBlockedUsers(final Long userId, final List<Long> userIds) {
        return userIds.stream()
            .filter(targetUserId -> canUsersInteract(userId, targetUserId))
            .collect(java.util.stream.Collectors.toList());
    }

    public boolean shouldHideUser(final Long viewerId, final Long targetUserId) {
        return isUserBlocked(viewerId, targetUserId) || isUserBlocked(targetUserId, viewerId);
    }

    public void processUserReport(final Long reporterId, final Long targetId, final int reportCount) {
        if (reportCount >= 5) {
            blockUser(1L, targetId, "Automatic block due to multiple reports");
            logger.info("User {} automatically blocked due to {} reports", targetId, reportCount);
        }
    }
}
