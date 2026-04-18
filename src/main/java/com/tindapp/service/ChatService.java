package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.Chat;
import com.tindapp.model.User;
import com.tindapp.repository.ChatRepository;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    public List<Chat> getUserChats(final Long userId, final int page, final int limit) {
        return chatRepository.findByParticipantId(userId, page, limit);
    }

    public int countUserChats(final Long userId) {
        return Math.toIntExact(chatRepository.countByParticipantId(userId));
    }

    public Optional<Chat> getChatById(final String chatId) {
        return chatRepository.findById(chatId);
    }

    public boolean isUserInChat(final String chatId, final Long userId) {
        return chatRepository.isParticipant(chatId, userId);
    }

    public FindCompanionResult findCompanion(final Long userId, final SearchFilters filters) {
        final User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        final int queueSize = companionQueue.getQueueSize();
        final int chatCost = calculateChatCost();

        if (chatCost > 0 && user.getBalance() < chatCost) {
            throw new RuntimeException("Insufficient balance");
        }

        if (companionQueue.isInQueue(userId)) {
            logger.info("User {} already in search queue, removing to start new search", userId);
            companionQueue.removeFromQueue(userId);
        }

        final CompanionQueue.SearchFilters queueFilters = new CompanionQueue.SearchFilters(
            filters.getGender(),
            filters.getAgeRange(),
            filters.getPreference(),
            filters.getCity()
        );

        final CompanionQueue.MatchResult queueResult = companionQueue.addToQueue(userId, queueFilters);

        if (queueResult != null) {
            final String existingChatId = queueResult.chatId();
            final Chat chat = chatRepository.findById(existingChatId)
                .orElseThrow(() -> new RuntimeException("Chat not found after match creation"));

            chat.getSettings().setCost(chatCost);
            chat.getSettings().setAnonymousId(anonymousChatCounter.getAndIncrement());
            chatRepository.save(chat);

            if (chatCost > 0) {
                user.setBalance(user.getBalance() - chatCost);
                userRepository.save(user);
            }

            final MatchResult matchResult = new MatchResult(
                chat.getId(),
                new CompanionInfo(
                    queueResult.companion().id(),
                    queueResult.companion().nickname(),
                    queueResult.companion().isVerified(),
                    queueResult.companion().isOnline()
                ),
                chatCost
            );

            return new FindCompanionResult(matchResult, false, queueSize, null);
        } else {
            return new FindCompanionResult(
                null,
                true,
                queueSize,
                "Поиск собеседника начат. Ожидайте уведомления о найденном собеседнике."
            );
        }
    }

    private int calculateChatCost() {
        return ChatPricingPolicy.calculateCost(userService.countOnlineUsers());
    }

    public int getChatCost() {
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

    public Chat endChat(final String chatId, final Long userId) {
        final Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.hasParticipant(userId)) {
            throw new RuntimeException("User is not a participant of this chat");
        }

        return closeChatInternal(chat, userId, Chat.ChatClosureReason.MANUAL);
    }

    public List<Chat> closeChatsBetween(final Long userId, final Long companionId, final Chat.ChatClosureReason reason) {
        final List<Chat> closedChats = new ArrayList<>();
        if (userId == null || companionId == null) {
            return closedChats;
        }

        for (final Chat.ChatType type : Chat.ChatType.values()) {
            chatRepository.findByParticipants(userId, companionId, type).ifPresent(chat -> {
                if (Boolean.TRUE.equals(chat.getIsActive())) {
                    final Chat closed = closeChatInternal(chat, userId, reason);
                    closedChats.add(closed);
                }
            });
        }

        return closedChats;
    }

    public List<Chat> reopenChatsBetween(final Long userId, final Long companionId) {
        final List<Chat> reopenedChats = new ArrayList<>();
        if (userId == null || companionId == null) {
            return reopenedChats;
        }

        final List<Chat> blockedChats = chatRepository.findByParticipants(userId, companionId, false, Chat.ChatClosureReason.BLOCKED);
        for (final Chat chat : blockedChats) {
            chat.setIsActive(true);
            chat.setClosureReason(null);
            chat.setClosedAt(null);
            chat.setClosedByUserId(null);
            final Chat reopened = chatRepository.save(chat);
            reopenedChats.add(reopened);
        }
        return reopenedChats;
    }

    public List<Chat> closeAllChatsForUser(final Long userId, final Chat.ChatClosureReason reason) {
        final List<Chat> closedChats = new ArrayList<>();
        if (userId == null) {
            return closedChats;
        }

        final List<Chat> activeChats = chatRepository.findByParticipantIdAndActive(userId, true);
        for (final Chat chat : activeChats) {
            final Chat closed = closeChatInternal(chat, userId, reason);
            closedChats.add(closed);
        }
        return closedChats;
    }

    public Chat startProfileChat(final Long initiatorId, final Long targetId) {
        if (initiatorId.equals(targetId)) {
            throw new RuntimeException("Нельзя начать чат с самим собой");
        }

        final User initiator = userRepository.findById(initiatorId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        final User target = userRepository.findById(targetId)
            .orElseThrow(() -> new RuntimeException("Target user not found"));

        if (!Boolean.TRUE.equals(target.getIsVisible())) {
            throw new RuntimeException("Target user is not available");
        }
        if (target.getSettings() != null && Boolean.FALSE.equals(target.getSettings().getAllowMessages())) {
            throw new RuntimeException("Target user disabled messages");
        }

        final Optional<Chat> existing = chatRepository.findByParticipants(initiatorId, targetId, Chat.ChatType.REGULAR);
        if (existing.isPresent()) {
            return existing.get();
        }

        final boolean initiatorHasSubscription = initiator.getSubscription() != null
            && Boolean.TRUE.equals(initiator.getSubscription().getIsActive());
        final int cost = initiatorHasSubscription ? 0 : calculateChatCost();
        if (cost > 0) {
            userService.deductCoins(initiatorId, cost);
        }

        final Chat chat = new Chat(UUID.randomUUID().toString(), Chat.ChatType.REGULAR, initiatorId, targetId);
        chat.getSettings().setCost(cost);
        chatRepository.save(chat);

        notificationService.sendProfileChatCreatedNotification(
            targetId,
            buildDisplayName(initiator)
        );

        return chat;
    }

    public boolean hasRegularChatBetween(final Long userId, final Long targetUserId) {
        if (userId == null || targetUserId == null) {
            return false;
        }
        return chatRepository.findByParticipants(userId, targetUserId, Chat.ChatType.REGULAR).isPresent();
    }

    public int getActiveAnonymousChatsCount() {
        return Math.toIntExact(chatRepository.countActiveByType(Chat.ChatType.ANONYMOUS));
    }

    private Chat closeChatInternal(final Chat chat, final Long closedByUserId, final Chat.ChatClosureReason reason) {
        if (!Boolean.TRUE.equals(chat.getIsActive())) {
            return chat;
        }

        chat.setIsActive(false);
        chat.setClosedByUserId(closedByUserId);
        chat.setClosureReason(reason);
        chat.setClosedAt(DateTimeUtils.nowAsIso());
        final Chat savedChat = chatRepository.save(chat);

        if (closedByUserId != null) {
            final Long companionId = chat.getCompanionId(closedByUserId);
            if (companionId != null) {
                final String closedByName =
                    chat.getType() == Chat.ChatType.ANONYMOUS
                        ? "Собеседник"
                        : buildDisplayName(userService.getUserById(closedByUserId).orElse(null));
                notificationService.sendDialogClosedNotification(companionId, chat.getType(), closedByName);
            }
        }

        return savedChat;
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

        public String getGender() {
            return gender;
        }

        public void setGender(final String gender) {
            this.gender = gender;
        }

        public int[] getAgeRange() {
            return ageRange;
        }

        public void setAgeRange(final int[] ageRange) {
            this.ageRange = ageRange;
        }

        public String getPreference() {
            return preference;
        }

        public void setPreference(final String preference) {
            this.preference = preference;
        }

        public String getCity() {
            return city;
        }

        public void setCity(final String city) {
            this.city = city;
        }
    }

    public record MatchResult(String chatId, CompanionInfo companion, int cost) {
    }

    public record CompanionInfo(Long id, String nickname, boolean isVerified, boolean isOnline) {
    }

        public record FindCompanionResult(MatchResult matchResult, boolean inQueue, int queueSize, String message) {
    }
}
