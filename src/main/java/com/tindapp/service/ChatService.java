package com.tindapp.service;

import com.tindapp.model.Chat;
import com.tindapp.model.User;
import com.tindapp.repository.ChatRepository;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.DateTimeUtils;
import com.tindapp.util.FutureUtils;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private static final AtomicInteger anonymousChatCounter = new AtomicInteger(1000);

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final CompanionQueue companionQueue;
    private final UserService userService;
    private final NotificationService notificationService;

    public ChatService(final ChatRepository chatRepository, final UserRepository userRepository, final UserService userService,
                       final NotificationService notificationService) {
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.notificationService = notificationService;
        companionQueue = new CompanionQueue(userService, chatRepository);
    }

    public Future<List<Chat>> getUserChats(final Long userId, final int page, final int limit) {
        return chatRepository.findByParticipantId(userId, page, limit);
    }

    public Future<Integer> countUserChats(final Long userId) {
        return chatRepository.countByParticipantId(userId).map(Math::toIntExact);
    }

    public Future<Optional<Chat>> getChatById(final String chatId) {
        return chatRepository.findById(chatId);
    }

    public Future<Boolean> isUserInChat(final String chatId, final Long userId) {
        return chatRepository.isParticipant(chatId, userId);
    }

    public Future<FindCompanionResult> findCompanion(final Long userId, final SearchFilters filters) {
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> calculateChatCost()
                .compose(chatCost -> {
                    if (chatCost > 0 && user.getBalance() < chatCost) {
                        return FutureUtils.failed("Insufficient balance");
                    }

                    if (companionQueue.isInQueue(userId)) {
                        companionQueue.removeFromQueue(userId);
                    }

                    final int queueSize = companionQueue.getQueueSize();
                    final CompanionQueue.SearchFilters queueFilters = new CompanionQueue.SearchFilters(
                        filters.getGender(),
                        filters.getAgeRange(),
                        filters.getPreference(),
                        filters.getCity()
                    );

                    return companionQueue.addToQueue(userId, queueFilters)
                        .compose(queueResult -> queueResult == null
                            ? Future.succeededFuture(new FindCompanionResult(
                                null,
                                true,
                                queueSize,
                                "Поиск собеседника начат. Ожидайте уведомления о найденном собеседнике."
                            ))
                            : finalizeMatchedChat(user, chatCost, queueSize, queueResult));
                }));
    }

    private Future<FindCompanionResult> finalizeMatchedChat(final User user, final int chatCost, final int queueSize,
                                                            final CompanionQueue.MatchResult queueResult) {
        return FutureUtils.requirePresent(chatRepository.findById(queueResult.chatId()), "Chat not found after match creation")
            .compose(chat -> {
                chat.getSettings().setCost(chatCost);
                chat.getSettings().setAnonymousId(anonymousChatCounter.getAndIncrement());
                final Future<User> balanceFuture = chatCost > 0
                    ? updateUserBalanceForChat(user, chatCost)
                    : Future.succeededFuture(user);
                return chatRepository.save(chat)
                    .compose(savedChat -> balanceFuture.map(updatedUser -> new FindCompanionResult(
                        new MatchResult(
                            savedChat.getId(),
                            new CompanionInfo(
                                queueResult.companion().id(),
                                queueResult.companion().nickname(),
                                queueResult.companion().isVerified(),
                                queueResult.companion().isOnline()
                            ),
                            chatCost
                        ),
                        false,
                        queueSize,
                        null
                    )));
            });
    }

    private Future<User> updateUserBalanceForChat(final User user, final int chatCost) {
        user.setBalance(user.getBalance() - chatCost);
        return userRepository.save(user);
    }

    private Future<Integer> calculateChatCost() {
        return Future.succeededFuture(ChatPricingPolicy.calculateAnonymousChatCost(companionQueue.getQueueSize()));
    }

    private Future<Integer> calculateProfileChatCost() {
        return userService.countOnlineUsers().map(ChatPricingPolicy::calculateProfileCost);
    }

    public Future<Integer> getChatCost() {
        return calculateChatCost();
    }

    public boolean cancelCompanionSearch(final Long userId) {
        return companionQueue.removeFromQueue(userId);
    }

    public boolean isSearchingCompanion(final Long userId) {
        return companionQueue.isInQueue(userId);
    }

    public int getSearchQueueSize() {
        return companionQueue.getQueueSize();
    }

    public Future<Chat> endChat(final String chatId, final Long userId) {
        return FutureUtils.requirePresent(chatRepository.findById(chatId), "Chat not found")
            .compose(chat -> {
                if (!chat.hasParticipant(userId)) {
                    return FutureUtils.failed("User is not a participant of this chat");
                }
                return closeChatInternal(chat, userId, Chat.ChatClosureReason.MANUAL);
            });
    }

    public Future<List<Chat>> closeChatsBetween(final Long userId, final Long companionId, final Chat.ChatClosureReason reason) {
        if (userId == null || companionId == null) {
            return Future.succeededFuture(List.of());
        }
        return FutureUtils.sequentialMap(List.of(Chat.ChatType.values()), type ->
                chatRepository.findByParticipants(userId, companionId, type)
                    .compose(chatOpt -> {
                        if (chatOpt.isEmpty() || !Boolean.TRUE.equals(chatOpt.get().getIsActive())) {
                            return Future.succeededFuture((Chat) null);
                        }
                        return closeChatInternal(chatOpt.get(), userId, reason);
                    }))
            .map(result -> result.stream().filter(java.util.Objects::nonNull).toList());
    }

    public Future<List<Chat>> reopenChatsBetween(final Long userId, final Long companionId) {
        if (userId == null || companionId == null) {
            return Future.succeededFuture(List.of());
        }
        return chatRepository.findByParticipants(userId, companionId, false, Chat.ChatClosureReason.BLOCKED)
            .compose(blockedChats -> FutureUtils.sequentialMap(blockedChats, chat -> {
                chat.setIsActive(true);
                chat.setClosureReason(null);
                chat.setClosedAt(null);
                chat.setClosedByUserId(null);
                return chatRepository.save(chat);
            }));
    }

    public Future<List<Chat>> closeAllChatsForUser(final Long userId, final Chat.ChatClosureReason reason) {
        if (userId == null) {
            return Future.succeededFuture(List.of());
        }
        return chatRepository.findByParticipantIdAndActive(userId, true)
            .compose(activeChats -> FutureUtils.sequentialMap(activeChats, chat -> closeChatInternal(chat, userId, reason)));
    }

    public Future<Chat> startProfileChat(final Long initiatorId, final Long targetId) {
        if (initiatorId.equals(targetId)) {
            return FutureUtils.failed("Нельзя начать чат с самим собой");
        }

        return FutureUtils.requirePresent(userRepository.findById(initiatorId), "User not found")
            .compose(initiator -> FutureUtils.requirePresent(userRepository.findById(targetId), "Target user not found")
                .compose(target -> {
                    if (!Boolean.TRUE.equals(target.getIsVisible())) {
                        return FutureUtils.failed("Target user is not available");
                    }
                    if (target.getSettings() != null && Boolean.FALSE.equals(target.getSettings().getAllowMessages())) {
                        return FutureUtils.failed("Target user disabled messages");
                    }

                    return chatRepository.findByParticipants(initiatorId, targetId, Chat.ChatType.REGULAR)
                        .compose(existing -> existing.isPresent()
                            ? Future.succeededFuture(existing.get())
                            : createProfileChat(initiator, target));
                }));
    }

    private Future<Chat> createProfileChat(final User initiator, final User target) {
        final boolean initiatorHasSubscription = initiator.getSubscription() != null
            && Boolean.TRUE.equals(initiator.getSubscription().getIsActive());
        final Future<Integer> costFuture = initiatorHasSubscription ? Future.succeededFuture(0) : calculateProfileChatCost();

        return costFuture.compose(cost -> {
            final Future<?> balanceFuture = cost > 0 ? userService.deductCoins(initiator.getId(), cost) : Future.succeededFuture();
            final Chat chat = new Chat(UUID.randomUUID().toString(), Chat.ChatType.REGULAR, initiator.getId(), target.getId());
            chat.getSettings().setCost(cost);
            return balanceFuture
                .compose(v -> chatRepository.save(chat))
                .compose(savedChat -> notificationService.sendProfileChatCreatedNotification(target.getId(), buildDisplayName(initiator))
                    .map(notification -> savedChat));
        });
    }

    public Future<Boolean> hasRegularChatBetween(final Long userId, final Long targetUserId) {
        if (userId == null || targetUserId == null) {
            return Future.succeededFuture(false);
        }
        return chatRepository.findByParticipants(userId, targetUserId, Chat.ChatType.REGULAR).map(Optional::isPresent);
    }

    private Future<Chat> closeChatInternal(final Chat chat, final Long closedByUserId, final Chat.ChatClosureReason reason) {
        if (!Boolean.TRUE.equals(chat.getIsActive())) {
            return Future.succeededFuture(chat);
        }

        chat.setIsActive(false);
        chat.setClosedByUserId(closedByUserId);
        chat.setClosureReason(reason);
        chat.setClosedAt(DateTimeUtils.nowAsIso());

        return chatRepository.save(chat)
            .compose(savedChat -> {
                if (closedByUserId == null) {
                    return Future.succeededFuture(savedChat);
                }
                final Long companionId = chat.getCompanionId(closedByUserId);
                if (companionId == null) {
                    return Future.succeededFuture(savedChat);
                }
                final Future<String> closedByNameFuture = chat.getType() == Chat.ChatType.ANONYMOUS
                    ? Future.succeededFuture("Собеседник")
                    : userService.getUserById(closedByUserId).map(user -> buildDisplayName(user.orElse(null)));
                return closedByNameFuture
                    .compose(closedByName -> notificationService.sendDialogClosedNotification(companionId, chat.getType(), closedByName))
                    .map(notification -> savedChat);
            });
    }

    private String buildDisplayName(final User user) {
        if (user == null) {
            return "Собеседник";
        }
        final String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        final String last = user.getLastName() != null ? user.getLastName().trim() : "";
        final String full = (first + ' ' + last).trim();
        if (!full.isEmpty()) {
            return full;
        }
        if (!first.isEmpty()) {
            return first;
        }
        if (!last.isEmpty()) {
            return last;
        }
        return "Собеседник #" + user.getId();
    }

    public static class SearchFilters {
        private String gender;
        private int[] ageRange;
        private String preference;
        private String city;

        public SearchFilters() {
        }

        public SearchFilters(final String gender, final int[] ageRange, final String preference, final String city) {
            this.gender = gender;
            this.ageRange = ageRange;
            this.preference = preference;
            this.city = city;
        }

        public String getGender() { return gender; }
        public void setGender(final String gender) { this.gender = gender; }
        public int[] getAgeRange() { return ageRange; }
        public void setAgeRange(final int[] ageRange) { this.ageRange = ageRange; }
        public String getPreference() { return preference; }
        public void setPreference(final String preference) { this.preference = preference; }
        public String getCity() { return city; }
        public void setCity(final String city) { this.city = city; }
    }

    public record MatchResult(String chatId, CompanionInfo companion, int cost) {
    }

    public record CompanionInfo(Long id, String nickname, boolean isVerified, boolean isOnline) {
    }

    public record FindCompanionResult(MatchResult matchResult, boolean inQueue, int queueSize, String message) {
    }
}
