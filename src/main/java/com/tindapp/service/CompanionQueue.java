package com.tindapp.service;

import com.tindapp.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    public CompanionQueue(UserService userService, com.tindapp.repository.ChatRepository chatRepository) {
        this.userService = userService;
        this.chatRepository = chatRepository;
    }

    public synchronized MatchResult addToQueue(Long userId, SearchFilters filters) {
        User user = userService.getUserById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (searchQueue.containsKey(userId)) {
            throw new RuntimeException("User already in search queue");
        }

        cleanupExpiredRequests();

        MatchResult existingMatch = findExistingMatch(userId, filters);
        if (existingMatch != null) {
            logger.info("Found immediate match for user: {} with companion: {}",
                       userId, existingMatch.getCompanion().getId());
            return existingMatch;
        }

        SearchRequest request = new SearchRequest(userId, filters, user, LocalDateTime.now());
        searchQueue.put(userId, request);

        logger.info("User {} added to companion search queue. Queue size: {}", userId, searchQueue.size());

        return null;
    }

    public synchronized boolean removeFromQueue(Long userId) {
        SearchRequest removed = searchQueue.remove(userId);
        if (removed != null) {
            logger.info("User {} removed from search queue. Queue size: {}", userId, searchQueue.size());
            return true;
        }
        return false;
    }

    public boolean isInQueue(Long userId) {
        return searchQueue.containsKey(userId);
    }

    public int getQueueSize() {
        return searchQueue.size();
    }

    private MatchResult findExistingMatch(Long userId, SearchFilters userFilters) {
        User currentUser = userService.getUserById(userId).orElse(null);
        if (currentUser == null) {
            logger.error("Cannot find current user: {}", userId);
            return null;
        }

        List<SearchRequest> compatibleRequests = new ArrayList<>();

        for (SearchRequest request : searchQueue.values()) {
            if (request.getUserId().equals(userId)) {
                continue;
            }

            if (!request.getUser().isOnline()) {
                continue;
            }

            String currentUserGender = mapUserGenderToString(currentUser.getGenderEnum());
            String companionGender = mapUserGenderToString(request.getUser().getGenderEnum());

            if (areFiltersCompatible(userFilters, request.getFilters(), currentUserGender, companionGender)) {
                compatibleRequests.add(request);
            } else {
            }
        }

        compatibleRequests.removeIf(request -> {
            Long companionId = request.getUserId();
            if (companionId == null) {
                return true;
            }
            boolean hasChat = hasActiveChatBetween(userId, companionId);
            if (hasChat) {
            }
            return hasChat;
        });

        if (compatibleRequests.isEmpty()) {
            return null;
        }

        SearchRequest companionRequest = compatibleRequests.get(
            ThreadLocalRandom.current().nextInt(compatibleRequests.size())
        );

        if (companionRequest.getUserId().equals(userId)) {
            logger.error("Critical error: attempted to match user with self: {}", userId);
            return null;
        }

        searchQueue.remove(companionRequest.getUserId());
        logger.info("Matched users: {} and {}", userId, companionRequest.getUserId());

        return createMatchResult(userId, companionRequest.getUser());
    }

    private boolean hasActiveChatBetween(Long userId, Long companionId) {
        return chatRepository.findByParticipantId(userId).stream()
            .anyMatch(chat -> Boolean.TRUE.equals(chat.getIsActive()) && chat.hasParticipant(companionId));
    }

    private boolean areFiltersCompatible(SearchFilters userFilters, SearchFilters companionFilters,
                                       String actualUserGender, String actualCompanionGender) {
        if (!isGenderFilterMatched(userFilters.getGender(), actualCompanionGender)) {
            return false;
        }

        if (!isGenderFilterMatched(companionFilters.getGender(), actualUserGender)) {
            return false;
        }

        if (userFilters.getCity() != null && companionFilters.getCity() != null) {
            if (!userFilters.getCity().equalsIgnoreCase(companionFilters.getCity())) {
                return false;
            }
        }

        return true;
    }

    private boolean isGenderFilterMatched(String genderFilter, String actualGender) {
        if ("any".equals(genderFilter)) {
            return true;
        }

        return genderFilter.equals(actualGender);
    }

    private String mapUserGenderToString(User.Gender gender) {
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

    private MatchResult createMatchResult(Long userId, User companion) {
        if (userId.equals(companion.getId())) {
            logger.error("Attempted to create match with self: userId={}", userId);
            return null;
        }

        String chatId = java.util.UUID.randomUUID().toString();
        com.tindapp.model.Chat chat = new com.tindapp.model.Chat(chatId, com.tindapp.model.Chat.ChatType.ANONYMOUS, userId, companion.getId());

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
        LocalDateTime cutoffTime = LocalDateTime.now().minus(QUEUE_TIMEOUT_MINUTES, ChronoUnit.MINUTES);

        searchQueue.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().getCreatedAt().isBefore(cutoffTime);
            if (expired) {
                logger.info("Removed expired search request for user: {}", entry.getKey());
            }
            return expired;
        });
    }

    public static class SearchRequest {
        private final Long userId;
        private final SearchFilters filters;
        private final User user;
        private final LocalDateTime createdAt;

        public SearchRequest(Long userId, SearchFilters filters, User user, LocalDateTime createdAt) {
            this.userId = userId;
            this.filters = filters;
            this.user = user;
            this.createdAt = createdAt;
        }

        public Long getUserId() { return userId; }
        public SearchFilters getFilters() { return filters; }
        public User getUser() { return user; }
        public LocalDateTime getCreatedAt() { return createdAt; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SearchRequest that = (SearchRequest) o;
            return Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId);
        }
    }

    public static class SearchFilters {
        private final String gender;
        private final int[] ageRange;
        private final String preference;
        private final String city;

        public SearchFilters(String gender, int[] ageRange, String preference, String city) {
            this.gender = gender;
            this.ageRange = ageRange;
            this.preference = preference;
            this.city = city;
        }

        public String getGender() { return gender; }
        public int[] getAgeRange() { return ageRange; }
        public String getPreference() { return preference; }
        public String getCity() { return city; }
    }

    public static class MatchResult {
        private final String chatId;
        private final CompanionInfo companion;
        private final int cost;

        public MatchResult(String chatId, CompanionInfo companion, int cost) {
            this.chatId = chatId;
            this.companion = companion;
            this.cost = cost;
        }

        public String getChatId() { return chatId; }
        public CompanionInfo getCompanion() { return companion; }
        public int getCost() { return cost; }
    }

    public static class CompanionInfo {
        private final Long id;
        private final String nickname;
        private final boolean isVerified;
        private final boolean isOnline;

        public CompanionInfo(Long id, String nickname, boolean isVerified, boolean isOnline) {
            this.id = id;
            this.nickname = nickname;
            this.isVerified = isVerified;
            this.isOnline = isOnline;
        }

        public Long getId() { return id; }
        public String getNickname() { return nickname; }
        public boolean isVerified() { return isVerified; }
        public boolean isOnline() { return isOnline; }
    }
}
