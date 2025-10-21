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

    public BlackListService(BlackListRepository blackListRepository, UserRepository userRepository) {
        this.blackListRepository = blackListRepository;
        this.userRepository = userRepository;
    }

    public BlackListItem blockUser(Long userId, Long blockedUserId, String reason) {
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

        String blackListId = UUID.randomUUID().toString();
        BlackListItem blackListItem = new BlackListItem(blackListId, userId, blockedUserId, reason);

        return blackListRepository.save(blackListItem);
    }

    public void unblockUser(Long userId, Long blockedUserId) {
        userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        userRepository.findById(blockedUserId)
            .orElseThrow(() -> new RuntimeException("Blocked user not found"));

        Optional<BlackListItem> blackListItem = blackListRepository.findByUserIdAndBlockedUserId(userId, blockedUserId);
        if (blackListItem.isPresent()) {
            blackListRepository.deleteById(blackListItem.get().getId());
        } else {
            throw new RuntimeException("User is not blocked");
        }
    }

    public List<BlackListItem> getUserBlackList(Long userId, int page, int limit) {
        return blackListRepository.findByUserId(userId, page, limit);
    }

    public List<BlackListItem> getUserBlackList(Long userId) {
        return blackListRepository.findByUserId(userId);
    }

    public boolean isUserBlocked(Long userId, Long blockedUserId) {
        return blackListRepository.isBlocked(userId, blockedUserId);
    }

    public boolean isUserBlockedByAnyone(Long userId) {
        List<BlackListItem> blockers = blackListRepository.findByBlockedUserId(userId);
        return !blockers.isEmpty();
    }

    public List<BlackListItem> getWhoBlockedUser(Long userId) {
        return blackListRepository.findByBlockedUserId(userId);
    }

    public long getBlockedUsersCount(Long userId) {
        return blackListRepository.countByUserId(userId);
    }

    public long getBlockersCount(Long userId) {
        return blackListRepository.countByBlockedUserId(userId);
    }

    public Optional<BlackListItem> getBlockInfo(Long userId, Long blockedUserId) {
        return blackListRepository.findByUserIdAndBlockedUserId(userId, blockedUserId);
    }

    public void deleteAllUserBlocks(Long userId) {
        List<BlackListItem> userBlocks = blackListRepository.findByUserId(userId);
        for (BlackListItem item : userBlocks) {
            blackListRepository.deleteById(item.getId());
        }
    }

    public boolean canUsersInteract(Long userId1, Long userId2) {
        return !isUserBlocked(userId1, userId2) && !isUserBlocked(userId2, userId1);
    }

    public List<Long> filterBlockedUsers(Long userId, List<Long> userIds) {
        return userIds.stream()
            .filter(targetUserId -> canUsersInteract(userId, targetUserId))
            .collect(java.util.stream.Collectors.toList());
    }

    public boolean shouldHideUser(Long viewerId, Long targetUserId) {
        return isUserBlocked(viewerId, targetUserId) || isUserBlocked(targetUserId, viewerId);
    }

    public void processUserReport(Long reporterId, Long targetId, int reportCount) {
        if (reportCount >= 5) {
            blockUser(1L, targetId, "Automatic block due to multiple reports");
            logger.info("User {} automatically blocked due to {} reports", targetId, reportCount);
        }
    }
}
