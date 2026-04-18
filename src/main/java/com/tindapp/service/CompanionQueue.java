package com.tindapp.service;

import com.tindapp.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class CompanionQueue {

    private static final Logger logger = LoggerFactory.getLogger(CompanionQueue.class);

    private static final int QUEUE_TIMEOUT_MINUTES = 10;

    private final Map<Long, SearchRequest> searchQueue = new ConcurrentHashMap<>();

    private final UserService userService;
    private final com.tindapp.repository.ChatRepository chatRepository;

    private static final java.util.concurrent.atomic.AtomicInteger anonymousChatCounter = new java.util.concurrent.atomic.AtomicInteger(1000);

    public CompanionQueue(final UserService userService, final com.tindapp.repository.ChatRepository chatRepository) {
        this.userService = userService;
        this.chatRepository = chatRepository;
    }

    public synchronized MatchResult addToQueue(final Long userId, final SearchFilters filters) {
        final User user = userService.getUserById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (searchQueue.containsKey(userId)) {
            throw new RuntimeException("User already in search queue");
        }

        cleanupExpiredRequests();

        final MatchResult existingMatch = findExistingMatch(userId, filters);
        if (existingMatch != null) {
            logger.info("Found immediate match for user: {} with companion: {}",
                userId, existingMatch.companion().id());
            return existingMatch;
        }

        final SearchRequest request = new SearchRequest(userId, filters, user, LocalDateTime.now());
        searchQueue.put(userId, request);

        logger.info("User {} added to companion search queue. Queue size: {}", userId, searchQueue.size());

        return null;
    }

    public synchronized boolean removeFromQueue(final Long userId) {
        final SearchRequest removed = searchQueue.remove(userId);
        if (removed != null) {
            logger.info("User {} removed from search queue. Queue size: {}", userId, searchQueue.size());
            return true;
        }
        return false;
    }

    public boolean isInQueue(final Long userId) {
        return searchQueue.containsKey(userId);
    }

    public int getQueueSize() {
        return searchQueue.size();
    }

    private MatchResult findExistingMatch(final Long userId, final SearchFilters userFilters) {
        final User currentUser = userService.getUserById(userId).orElse(null);
        if (currentUser == null) {
            logger.error("Cannot find current user: {}", userId);
            return null;
        }

        final List<SearchRequest> compatibleRequests = new ArrayList<>();

        for (final SearchRequest request : searchQueue.values()) {
            if (request.userId().equals(userId)) {
                continue;
            }

            if (!request.user().isOnline()) {
                continue;
            }

            final String currentUserGender = mapUserGenderToString(currentUser.getGenderEnum());
            final String companionGender = mapUserGenderToString(request.user().getGenderEnum());

            if (areFiltersCompatible(userFilters, request.filters(), currentUserGender, companionGender)) {
                compatibleRequests.add(request);
            } else {
            }
        }

        compatibleRequests.removeIf(request -> {
            final Long companionId = request.userId();
            if (companionId == null) {
                return true;
            }
            final boolean hasChat = hasActiveChatBetween(userId, companionId);
            if (hasChat) {
            }
            return hasChat;
        });

        if (compatibleRequests.isEmpty()) {
            return null;
        }

        final SearchRequest companionRequest = compatibleRequests.get(
            ThreadLocalRandom.current().nextInt(compatibleRequests.size())
        );

        if (companionRequest.userId().equals(userId)) {
            logger.error("Critical error: attempted to match user with self: {}", userId);
            return null;
        }

        searchQueue.remove(companionRequest.userId());
        logger.info("Matched users: {} and {}", userId, companionRequest.userId());

        return createMatchResult(userId, companionRequest.user());
    }

    private boolean hasActiveChatBetween(final Long userId, final Long companionId) {
        return chatRepository.existsActiveBetweenParticipants(userId, companionId);
    }

    private boolean areFiltersCompatible(final SearchFilters userFilters, final SearchFilters companionFilters,
                                         final String actualUserGender, final String actualCompanionGender) {
        if (!isGenderFilterMatched(userFilters.gender(), actualCompanionGender)) {
            return false;
        }

        if (!isGenderFilterMatched(companionFilters.gender(), actualUserGender)) {
            return false;
        }

        if (userFilters.city() != null && companionFilters.city() != null) {
            return userFilters.city().equalsIgnoreCase(companionFilters.city());
        }

        return true;
    }

    private boolean isGenderFilterMatched(final String genderFilter, final String actualGender) {
        if ("any".equals(genderFilter)) {
            return true;
        }

        return genderFilter.equals(actualGender);
    }

    private String mapUserGenderToString(final User.Gender gender) {
        if (gender == null) {
            return "other";
        }

        switch (gender) {
            case MALE:
                return "male";
            case FEMALE:
                return "female";
            case OTHER:
            default:
                return "other";
        }
    }

    private MatchResult createMatchResult(final Long userId, final User companion) {
        if (userId.equals(companion.getId())) {
            logger.error("Attempted to create match with self: userId={}", userId);
            return null;
        }

        final String chatId = java.util.UUID.randomUUID().toString();
        final com.tindapp.model.Chat chat = new com.tindapp.model.Chat(chatId, com.tindapp.model.Chat.ChatType.ANONYMOUS, userId, companion.getId());

        chat.getSettings().setCost(0); // Стоимость будет рассчитана в ChatService
        chat.getSettings().setAnonymousId(anonymousChatCounter.getAndIncrement()); // Генерируем анонимный ID

        chatRepository.save(chat);

        logger.info("Creating match between users {} and {} with chatId {}", userId, companion.getId(), chatId);

        return new MatchResult(
            chatId,
            new CompanionInfo(
                companion.getId(),
                "Собеседник #" + companion.getId(),
                companion.isVerified(),
                companion.isOnline()
            ),
            0 // cost будет рассчитан отдельно
        );
    }

    private void cleanupExpiredRequests() {
        final LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(QUEUE_TIMEOUT_MINUTES);

        searchQueue.entrySet().removeIf(entry -> {
            final boolean expired = entry.getValue().createdAt().isBefore(cutoffTime);
            if (expired) {
                logger.info("Removed expired search request for user: {}", entry.getKey());
            }
            return expired;
        });
    }

        public record SearchRequest(Long userId, SearchFilters filters, User user, LocalDateTime createdAt) {

        @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                final SearchRequest that = (SearchRequest) o;
                return Objects.equals(userId, that.userId);
            }

            @Override
            public int hashCode() {
                return Objects.hash(userId);
            }
        }

        public record SearchFilters(String gender, int[] ageRange, String preference, String city) {
    }

        public record MatchResult(String chatId, CompanionInfo companion, int cost) {
    }

        public record CompanionInfo(Long id, String nickname, boolean isVerified, boolean isOnline) {
    }
}
