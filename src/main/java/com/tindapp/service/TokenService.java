package com.tindapp.service;

import com.tindapp.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TokenService {

    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);

    private static final int TOKEN_EXPIRY_HOURS = 24;

    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();

    private final Map<String, User> userTokens = new ConcurrentHashMap<>();

    private final Map<Long, String> userToToken = new ConcurrentHashMap<>();

    private final UserService userService;
    private final ScheduledExecutorService cleanupExecutor;

    public TokenService(UserService userService) {
        this.userService = userService;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredTokens, 1, 1, TimeUnit.HOURS);
    }

    public String createToken(User user) {
        if (user == null) {
            logger.error("Cannot create token: user is null");
            throw new IllegalArgumentException("User cannot be null");
        }

        if (user.getId() == null) {
            logger.error("Cannot create token: user ID is null for vkId={}", user.getVkId());
            throw new IllegalArgumentException("User ID cannot be null");
        }

        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS);

        TokenInfo tokenInfo = new TokenInfo(
            user.getId(),
            LocalDateTime.now(),
            expiresAt,
            System.currentTimeMillis()
        );

        removeUserToken(user.getId());

        User refreshedUser = userService.getUserById(user.getId()).orElse(user);

        tokens.put(token, tokenInfo);
        userTokens.put(token, refreshedUser);
        userToToken.put(user.getId(), token);

        return token;
    }

    public User validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }

        TokenInfo tokenInfo = tokens.get(token);
        if (tokenInfo == null) {
            return null;
        }

        if (tokenInfo.isExpired()) {
            removeToken(token);
            return null;
        }

        tokenInfo.updateLastUsed();

        User user = userTokens.get(token);
        if (user != null) {
            if (user.getId() == null) {
                logger.error("Token validation failed: user ID is null for vkId={}, removing token", user.getVkId());
                removeToken(token);
                return null;
            }

            User freshUser = userService.getUserById(user.getId()).orElse(user);

            userTokens.put(token, freshUser);

            return freshUser;
        } else {
            logger.warn("Token validation failed: user not found for token {}", token);
        }

        return null;
    }

    public TokenInfo getTokenInfo(String token) {
        return tokens.get(token);
    }

    public int getActiveTokensCount() {
        return tokens.size();
    }

    public boolean hasActiveToken(Long userId) {
        String token = userToToken.get(userId);
        if (token == null) {
            return false;
        }

        TokenInfo tokenInfo = tokens.get(token);
        return tokenInfo != null && !tokenInfo.isExpired();
    }

    public String getUserToken(Long userId) {
        String token = userToToken.get(userId);
        if (token != null) {
            TokenInfo tokenInfo = tokens.get(token);
            if (tokenInfo != null && !tokenInfo.isExpired()) {
                return token;
            }
        }
        return null;
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "") +
               System.currentTimeMillis() +
               UUID.randomUUID().toString().replace("-", "");
    }

    private void removeToken(String token) {
        TokenInfo tokenInfo = tokens.remove(token);
        userTokens.remove(token);

        if (tokenInfo != null) {
            userToToken.remove(tokenInfo.getUserId());
        }
    }

    private void removeUserToken(Long userId) {
        String oldToken = userToToken.get(userId);
        if (oldToken != null) {
            removeToken(oldToken);
        }
    }

    private void cleanupExpiredTokens() {
        int removedCount = 0;
        LocalDateTime now = LocalDateTime.now();

        var iterator = tokens.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String token = entry.getKey();
            TokenInfo tokenInfo = entry.getValue();

            if (tokenInfo.getExpiresAt().isBefore(now)) {
                iterator.remove();
                userTokens.remove(token);
                userToToken.remove(tokenInfo.getUserId());
                removedCount++;
            }
        }

        if (removedCount > 0) {
            logger.info("Cleaned up {} expired tokens", removedCount);
        }
    }

    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        tokens.clear();
        userTokens.clear();
        userToToken.clear();

        logger.info("TokenService shut down");
    }

    public static class TokenInfo {
        private final Long userId;
        private final LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private long lastUsed;

        public TokenInfo(Long userId, LocalDateTime createdAt, LocalDateTime expiresAt, long lastUsed) {
            this.userId = userId;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.lastUsed = lastUsed;
        }

        public Long getUserId() {
            return userId;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public LocalDateTime getExpiresAt() {
            return expiresAt;
        }

        public long getLastUsed() {
            return lastUsed;
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }

        public void updateLastUsed() {
            this.lastUsed = System.currentTimeMillis();
        }

        public void extendExpiry(int hours) {
            this.expiresAt = LocalDateTime.now().plus(hours, ChronoUnit.HOURS);
        }

        @Override
        public String toString() {
            return "TokenInfo{" +
                    "userId=" + userId +
                    ", createdAt=" + createdAt +
                    ", expiresAt=" + expiresAt +
                    ", lastUsed=" + lastUsed +
                    '}';
        }
    }
}
