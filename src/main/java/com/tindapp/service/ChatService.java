package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.Chat;
import com.tindapp.model.User;
import com.tindapp.repository.ChatRepository;
import com.tindapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private static final int ANONYMOUS_CHAT_COST = AppConfig.ANONYMOUS_CHAT_CREATION_COST;
    private static final int FREE_CHAT_USER_THRESHOLD = 10; // Если пользователей меньше 10, чат бесплатный

    private static final AtomicInteger anonymousChatCounter = new AtomicInteger(1000);

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final CompanionQueue companionQueue;

    public ChatService(ChatRepository chatRepository, UserRepository userRepository, UserService userService) {
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
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

        int chatCost = calculateChatCost();

        if (chatCost > 0 && user.getBalance() < chatCost) {
            throw new RuntimeException("Insufficient balance");
        }

        Optional<Chat> existingChat = chatRepository.findActiveAnonymousChat(userId);
        if (existingChat.isPresent()) {
            logger.info("User {} has active chat {}, ending it to start new search", userId, existingChat.get().getId());
            endChat(existingChat.get().getId(), userId);
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

            return new FindCompanionResult(matchResult, false, 0, null);
        } else {
            return new FindCompanionResult(
                null,
                true,
                companionQueue.getQueueSize(),
                "Поиск собеседника начат. Ожидайте уведомления о найденном собеседнике."
            );
        }
    }

    private int calculateChatCost() {
        long totalUsers = userRepository.count();

        if (totalUsers < FREE_CHAT_USER_THRESHOLD) {
            logger.info("Chat is FREE: {} users (threshold: {})", totalUsers, FREE_CHAT_USER_THRESHOLD);
            return 0; // Бесплатно
        } else {
            logger.info("Chat costs {} coins: {} users (threshold: {})", ANONYMOUS_CHAT_COST, totalUsers, FREE_CHAT_USER_THRESHOLD);
            return ANONYMOUS_CHAT_COST; // Платно
        }
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

        chat.setIsActive(false);
        return chatRepository.save(chat);
    }

    public void markMessagesAsRead(String chatId, Long userId, List<String> messageIds) {
        Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.hasParticipant(userId)) {
            throw new RuntimeException("User is not a participant of this chat");
        }

        chat.resetUnreadCount();
        chatRepository.save(chat);
    }

    public void updateTypingStatus(String chatId, Long userId, boolean isTyping) {
        Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.hasParticipant(userId)) {
            throw new RuntimeException("User is not a participant of this chat");
        }

        chat.updateActivity();
        chatRepository.save(chat);
    }

    private List<User> findMatchingUsers(Long userId, SearchFilters filters) {
        User.Gender targetGender = null;
        if (!"any".equals(filters.getGender())) {
            targetGender = User.Gender.valueOf(filters.getGender().toUpperCase());
        }

        List<User> candidates = userRepository.findForMatching(
            targetGender,
            filters.getAgeRange()[0],
            filters.getAgeRange()[1],
            filters.getCity()
        );

        candidates = candidates.stream()
            .filter(u -> !u.getId().equals(userId))
            .filter(u -> Boolean.TRUE.equals(u.getIsOnline())) // только онлайн пользователи
            .collect(Collectors.toList());

        return candidates.stream()
            .filter(u -> chatRepository.findActiveAnonymousChat(u.getId()).isEmpty())
            .collect(Collectors.toList());
    }

    private Chat createAnonymousChat(Long userId1, Long userId2) {
        String chatId = UUID.randomUUID().toString();
        logger.info("Creating anonymous chat: chatId={}, user1={}, user2={}", chatId, userId1, userId2);

        Chat chat = new Chat(chatId, Chat.ChatType.ANONYMOUS, userId1, userId2);

        chat.getSettings().setCost(ANONYMOUS_CHAT_COST);
        chat.getSettings().setAnonymousId(anonymousChatCounter.getAndIncrement());

        Chat savedChat = chatRepository.save(chat);
        logger.info("Anonymous chat created successfully: chatId={}, anonymousId={}",
                   savedChat.getId(), savedChat.getSettings().getAnonymousId());

        return savedChat;
    }

    public int getActiveAnonymousChatsCount() {
        return chatRepository.findByType(Chat.ChatType.ANONYMOUS).size();
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
