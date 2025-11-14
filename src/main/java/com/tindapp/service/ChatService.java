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
    private static final int ANONYMOUS_CHAT_COST = AppConfig.ANONYMOUS_CHAT_CREATION_COST;
    private static final int FREE_CHAT_USER_THRESHOLD = 10; // Если пользователей меньше 10, чат бесплатный

    private static final AtomicInteger anonymousChatCounter = new AtomicInteger(1000);

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final CompanionQueue companionQueue;
    private final UserService userService;
    private final NotificationService notificationService;

    public ChatService(ChatRepository chatRepository, UserRepository userRepository, UserService userService,
                       NotificationService notificationService) {
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.notificationService = notificationService;
        this.companionQueue = new CompanionQueue(userService, chatRepository);
    }

    public List<Chat> getUserChats(Long userId, int page, int limit) {
        return chatRepository.findByParticipantId(userId, page, limit);
    }

    public Optional<Chat> getChatById(String chatId) {
        return chatRepository.findById(chatId);
    }

    public boolean isUserInChat(String chatId, Long userId) {
        return chatRepository.isParticipant(chatId, userId);
    }

    public FindCompanionResult findCompanion(Long userId, SearchFilters filters) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        int queueSize = companionQueue.getQueueSize();
        int chatCost = calculateChatCost(queueSize);

        if (chatCost > 0 && user.getBalance() < chatCost) {
            throw new RuntimeException("Insufficient balance");
        }

        if (companionQueue.isInQueue(userId)) {
            logger.info("User {} already in search queue, removing to start new search", userId);
            companionQueue.removeFromQueue(userId);
        }

        CompanionQueue.SearchFilters queueFilters = new CompanionQueue.SearchFilters(
            filters.getGender(),
            filters.getAgeRange(),
            filters.getPreference(),
            filters.getCity()
        );

        CompanionQueue.MatchResult queueResult = companionQueue.addToQueue(userId, queueFilters);

        if (queueResult != null) {
            String existingChatId = queueResult.getChatId();
            Chat chat = chatRepository.findById(existingChatId)
                .orElseThrow(() -> new RuntimeException("Chat not found after match creation"));

            chat.getSettings().setCost(chatCost);
            chat.getSettings().setAnonymousId(anonymousChatCounter.getAndIncrement());
            chatRepository.save(chat);

            if (chatCost > 0) {
                user.setBalance(user.getBalance() - chatCost);
                userRepository.save(user);
            }

            MatchResult matchResult = new MatchResult(
                chat.getId(),
                new CompanionInfo(
                    queueResult.getCompanion().getId(),
                    queueResult.getCompanion().getNickname(),
                    queueResult.getCompanion().isVerified(),
                    queueResult.getCompanion().isOnline()
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

    private static int calculateChatCost(int queueSize) {
        if (queueSize >= AppConfig.SEARCH_QUEUE_PAID_THRESHOLD) {
            return AppConfig.ANONYMOUS_CHAT_CREATION_COST;
        }

        return 0;
    }

    private int calculateChatCost() {
        return calculateChatCost(companionQueue.getQueueSize());
    }

    public int getChatCost() {
        return calculateChatCost();
    }

    public boolean cancelCompanionSearch(Long userId) {
        return companionQueue.removeFromQueue(userId);
    }

    public boolean isSearchingCompanion(Long userId) {
        return companionQueue.isInQueue(userId);
    }

    public int getSearchQueueSize() {
        return companionQueue.getQueueSize();
    }

    public Chat endChat(String chatId, Long userId) {
        Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.hasParticipant(userId)) {
            throw new RuntimeException("User is not a participant of this chat");
        }

        return closeChatInternal(chat, userId, Chat.ChatClosureReason.MANUAL);
    }

    public List<Chat> closeChatsBetween(Long userId, Long companionId, Chat.ChatClosureReason reason) {
        List<Chat> closedChats = new ArrayList<>();
        if (userId == null || companionId == null) {
            return closedChats;
        }

        for (Chat.ChatType type : Chat.ChatType.values()) {
            chatRepository.findByParticipants(userId, companionId, type).ifPresent(chat -> {
                if (Boolean.TRUE.equals(chat.getIsActive())) {
                    Chat closed = closeChatInternal(chat, userId, reason);
                    closedChats.add(closed);
                }
            });
        }

        return closedChats;
    }

    public List<Chat> reopenChatsBetween(Long userId, Long companionId) {
        List<Chat> reopenedChats = new ArrayList<>();
        if (userId == null || companionId == null) {
            return reopenedChats;
        }

        List<Chat> userChats = chatRepository.findByParticipantId(userId);
        for (Chat chat : userChats) {
            if (!chat.hasParticipant(companionId)) {
                continue;
            }
            if (!Boolean.TRUE.equals(chat.getIsActive()) && chat.getClosureReason() == Chat.ChatClosureReason.BLOCKED) {
                chat.setIsActive(true);
                chat.setClosureReason(null);
                chat.setClosedAt(null);
                chat.setClosedByUserId(null);
                Chat reopened = chatRepository.save(chat);
                reopenedChats.add(reopened);
            }
        }
        return reopenedChats;
    }

    public List<Chat> closeAllChatsForUser(Long userId, Chat.ChatClosureReason reason) {
        List<Chat> closedChats = new ArrayList<>();
        if (userId == null) {
            return closedChats;
        }

        List<Chat> userChats = chatRepository.findByParticipantId(userId);
        for (Chat chat : userChats) {
            if (Boolean.TRUE.equals(chat.getIsActive())) {
                Chat closed = closeChatInternal(chat, userId, reason);
                closedChats.add(closed);
            }
        }
        return closedChats;
    }

    public Chat startProfileChat(Long initiatorId, Long targetId) {
        if (initiatorId.equals(targetId)) {
            throw new RuntimeException("Нельзя начать чат с самим собой");
        }

        User initiator = userRepository.findById(initiatorId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        User target = userRepository.findById(targetId)
            .orElseThrow(() -> new RuntimeException("Target user not found"));

        if (!Boolean.TRUE.equals(target.getIsVisible())) {
            throw new RuntimeException("Target user is not available");
        }
        if (target.getSettings() != null && Boolean.FALSE.equals(target.getSettings().getAllowMessages())) {
            throw new RuntimeException("Target user disabled messages");
        }

        Optional<Chat> existing = chatRepository.findByParticipants(initiatorId, targetId, Chat.ChatType.REGULAR);
        if (existing.isPresent()) {
            return existing.get();
        }

        int cost = target.getProfileCost() != null ? target.getProfileCost() : AppConfig.ANONYMOUS_CHAT_CREATION_COST;
        if (cost > 0) {
            userService.deductCoins(initiatorId, cost);
        }

        Chat chat = new Chat(UUID.randomUUID().toString(), Chat.ChatType.REGULAR, initiatorId, targetId);
        chat.getSettings().setCost(cost);
        chatRepository.save(chat);

        notificationService.sendProfileChatCreatedNotification(
            targetId,
            buildDisplayName(initiator)
        );

        return chat;
    }

    public boolean hasRegularChatBetween(Long userId, Long targetUserId) {
        if (userId == null || targetUserId == null) {
            return false;
        }
        return chatRepository.findByParticipants(userId, targetUserId, Chat.ChatType.REGULAR).isPresent();
    }

    public int getActiveAnonymousChatsCount() {
        return chatRepository.findByType(Chat.ChatType.ANONYMOUS).size();
    }

    private Chat closeChatInternal(Chat chat, Long closedByUserId, Chat.ChatClosureReason reason) {
        if (!Boolean.TRUE.equals(chat.getIsActive())) {
            return chat;
        }

        chat.setIsActive(false);
        chat.setClosedByUserId(closedByUserId);
        chat.setClosureReason(reason);
        chat.setClosedAt(DateTimeUtils.nowAsIso());
        Chat savedChat = chatRepository.save(chat);

        if (closedByUserId != null) {
            Long companionId = chat.getCompanionId(closedByUserId);
            if (companionId != null) {
                String closedByName =
                    chat.getType() == Chat.ChatType.ANONYMOUS
                        ? "Собеседник"
                        : buildDisplayName(userService.getUserById(closedByUserId).orElse(null));
                notificationService.sendDialogClosedNotification(companionId, chat.getType(), closedByName);
            }
        }

        return savedChat;
    }

    private String buildDisplayName(User user) {
        if (user == null) {
            return "Собеседник";
        }
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";
        String full = (first + " " + last).trim();
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

        public SearchFilters() {}

        public SearchFilters(String gender, int[] ageRange, String preference, String city) {
            this.gender = gender;
            this.ageRange = ageRange;
            this.preference = preference;
            this.city = city;
        }

        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }

        public int[] getAgeRange() { return ageRange; }
        public void setAgeRange(int[] ageRange) { this.ageRange = ageRange; }

        public String getPreference() { return preference; }
        public void setPreference(String preference) { this.preference = preference; }

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
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
        public boolean getIsVerified() { return isVerified; }
        public boolean getIsOnline() { return isOnline; }
    }

    public static class FindCompanionResult {
        private final MatchResult matchResult;
        private final boolean inQueue;
        private final int queueSize;
        private final String message;

        public FindCompanionResult(MatchResult matchResult, boolean inQueue, int queueSize, String message) {
            this.matchResult = matchResult;
            this.inQueue = inQueue;
            this.queueSize = queueSize;
            this.message = message;
        }

        public MatchResult getMatchResult() { return matchResult; }
        public boolean isInQueue() { return inQueue; }
        public int getQueueSize() { return queueSize; }
        public String getMessage() { return message; }
    }
}
