package com.tindapp.service;

import com.tindapp.model.Chat;
import com.tindapp.model.User;
import com.tindapp.repository.ChatRepository;
import com.tindapp.util.FutureUtils;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class CompanionQueue {

    private static final Logger logger = LoggerFactory.getLogger(CompanionQueue.class);
    private static final int QUEUE_TIMEOUT_MINUTES = 10;

    private final Map<Long, SearchRequest> searchQueue = new ConcurrentHashMap<>();
    private final UserService userService;
    private final ChatRepository chatRepository;
    private static final AtomicInteger anonymousChatCounter = new AtomicInteger(1000);

    public CompanionQueue(final UserService userService, final ChatRepository chatRepository) {
        this.userService = userService;
        this.chatRepository = chatRepository;
    }

    public synchronized Future<MatchResult> addToQueue(final Long userId, final SearchFilters filters) {
        cleanupExpiredRequests();

        if (searchQueue.containsKey(userId)) {
            return FutureUtils.failed("User already in search queue");
        }

        return FutureUtils.requirePresent(userService.getUserById(userId), "User not found")
            .compose(user -> findExistingMatch(userId, filters, user)
                .compose(existingMatch -> {
                    if (existingMatch != null) {
                        logger.info("Found immediate match for user: {} with companion: {}", userId, existingMatch.companion().id());
                        return Future.succeededFuture(existingMatch);
                    }
                    searchQueue.put(userId, new SearchRequest(userId, filters, user, LocalDateTime.now()));
                    logger.info("User {} added to companion search queue. Queue size: {}", userId, searchQueue.size());
                    return Future.succeededFuture((MatchResult) null);
                }));
    }

    public synchronized boolean removeFromQueue(final Long userId) {
        final SearchRequest removed = searchQueue.remove(userId);
        if (removed != null) {
            logger.info("User {} removed from search queue. Queue size: {}", userId, searchQueue.size());
            return true;
        }
        return false;
    }

    public synchronized boolean isInQueue(final Long userId) {
        cleanupExpiredRequests();
        return searchQueue.containsKey(userId);
    }

    public synchronized int getQueueSize() {
        cleanupExpiredRequests();
        return searchQueue.size();
    }

    private Future<MatchResult> findExistingMatch(final Long userId, final SearchFilters userFilters, final User currentUser) {
        if (currentUser == null) {
            return Future.succeededFuture((MatchResult) null);
        }

        final List<SearchRequest> compatibleRequests = new ArrayList<>();
        for (final SearchRequest request : searchQueue.values()) {
            if (request.userId().equals(userId) || !request.user().isOnline()) {
                continue;
            }
            final String currentUserGender = mapUserGenderToString(currentUser.getGenderEnum());
            final String companionGender = mapUserGenderToString(request.user().getGenderEnum());
            if (areFiltersCompatible(userFilters, request.filters(), currentUserGender, companionGender)) {
                compatibleRequests.add(request);
            }
        }

        return filterRequestsWithoutActiveChat(userId, compatibleRequests)
            .compose(filtered -> {
                if (filtered.isEmpty()) {
                    return Future.succeededFuture((MatchResult) null);
                }
                final SearchRequest companionRequest = filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));
                if (companionRequest.userId().equals(userId)) {
                    return Future.succeededFuture((MatchResult) null);
                }
                searchQueue.remove(companionRequest.userId());
                logger.info("Matched users: {} and {}", userId, companionRequest.userId());
                return createMatchResult(userId, companionRequest.user());
            });
    }

    private Future<List<SearchRequest>> filterRequestsWithoutActiveChat(final Long userId, final List<SearchRequest> requests) {
        return FutureUtils.sequentialMap(requests, request ->
                chatRepository.existsActiveBetweenParticipants(userId, request.userId())
                    .map(hasChat -> hasChat ? null : request))
            .map(result -> result.stream().filter(Objects::nonNull).toList());
    }

    private boolean areFiltersCompatible(final SearchFilters userFilters, final SearchFilters companionFilters,
                                         final String actualUserGender, final String actualCompanionGender) {
        if (!isGenderFilterMatched(userFilters.gender(), actualCompanionGender)) {
            return false;
        }
        if (!isGenderFilterMatched(companionFilters.gender(), actualUserGender)) {
            return false;
        }
        return userFilters.city() == null || companionFilters.city() == null
            || userFilters.city().equalsIgnoreCase(companionFilters.city());
    }

    private boolean isGenderFilterMatched(final String genderFilter, final String actualGender) {
        return "any".equals(genderFilter) || genderFilter.equals(actualGender);
    }

    private String mapUserGenderToString(final User.Gender gender) {
        return switch (gender == null ? User.Gender.OTHER : gender) {
            case MALE -> "male";
            case FEMALE -> "female";
            case OTHER -> "other";
        };
    }

    private Future<MatchResult> createMatchResult(final Long userId, final User companion) {
        if (userId.equals(companion.getId())) {
            return Future.succeededFuture((MatchResult) null);
        }

        final String chatId = java.util.UUID.randomUUID().toString();
        final Chat chat = new Chat(chatId, Chat.ChatType.ANONYMOUS, userId, companion.getId());
        chat.getSettings().setCost(0);
        chat.getSettings().setAnonymousId(anonymousChatCounter.getAndIncrement());

        return chatRepository.save(chat).map(savedChat -> new MatchResult(
            savedChat.getId(),
            new CompanionInfo(companion.getId(), "Собеседник #" + companion.getId(), companion.isVerified(), companion.isOnline()),
            0
        ));
    }

    private void cleanupExpiredRequests() {
        final LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(QUEUE_TIMEOUT_MINUTES);
        searchQueue.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoffTime));
    }

    public record SearchRequest(Long userId, SearchFilters filters, User user, LocalDateTime createdAt) {
        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
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
