package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private static final Set<Long> ADMIN_VK_IDS = java.util.Arrays.stream(System.getenv("ADMIN_VK_IDS").split(","))
        .map(Long::parseLong)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createOrUpdateUser(Long vkId) {
        Optional<User> existingUser = userRepository.findByVkId(vkId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.updateLastSeen();
            ensureProfileCost(user);
            ensureRewards(user);
            applySpecialPrivileges(user);
            return userRepository.save(user);
        } else {
            User newUser = new User(vkId);
            newUser.setBalance(AppConfig.INITIAL_USER_BALANCE); // начальный баланс
            ensureProfileCost(newUser);
            ensureRewards(newUser);
            applySpecialPrivileges(newUser);
            return userRepository.save(newUser);
        }
    }

    public User getOrCreateUser(Long vkId) {
        Optional<User> existingUser = userRepository.findByVkId(vkId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.updateLastSeen();
            ensureProfileCost(user);
            ensureRewards(user);
            applySpecialPrivileges(user);
            return userRepository.save(user);
        } else {
            User newUser = new User(vkId);
            ensureProfileCost(newUser);
            ensureRewards(newUser);
            applySpecialPrivileges(newUser);
            return userRepository.save(newUser);
        }
    }

    public Optional<User> getUserById(Long userId) {
        return userRepository.findById(userId);
    }

    public Optional<User> getUserByVkId(Long vkId) {
        return userRepository.findByVkId(vkId);
    }

    public User createUser(User user) {
        user.setCreatedAtDateTime(LocalDateTime.now());
        user.setUpdatedAtDateTime(LocalDateTime.now());
        ensureProfileCost(user);
        ensureRewards(user);
        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public User updateUser(User user) {
        user.setUpdatedAtDateTime(LocalDateTime.now());
        ensureRewards(user);
        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public User updateProfile(
        Long userId,
        String firstName,
        String lastName,
        String avatarUrl,
        String gender,
        String bio,
        String country,
        String city,
        Integer age,
        String birthDate,
        Boolean isVisible,
        User.UserSettings settings,
        Integer profileCost,
        String nativeLanguage
    ) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        ensureRewards(user);

        if (firstName != null) {
            user.setFirstName(firstName);
        }
        if (lastName != null) {
            user.setLastName(lastName);
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
        }
        if (gender != null) {
            user.setGender(gender);
        }
        if (bio != null) {
            user.setBio(bio);
        }
        if (country != null) {
            user.setCountry(country);
        }
        if (city != null) {
            user.setCity(city);
        }
        LocalDate parsedBirthDate = parseBirthDate(birthDate);
        if (parsedBirthDate != null) {
            user.setBirthDate(parsedBirthDate);
            user.setAge(calculateAge(parsedBirthDate));
        } else if (age != null) {
            user.setAge(age);
        }
        if (isVisible != null) {
            user.setIsVisible(isVisible);
        }
        if (settings != null) {
            User.UserSettings currentSettings = user.getSettings();
            if (currentSettings == null) {
                currentSettings = new User.UserSettings();
            }

            if (settings.getShowAge() != null) {
                currentSettings.setShowAge(settings.getShowAge());
            }
            if (settings.getShowCity() != null) {
                currentSettings.setShowCity(settings.getShowCity());
            }
            if (settings.getAllowMessages() != null) {
                currentSettings.setAllowMessages(settings.getAllowMessages());
            }
            if (settings.getAllowCommunityMessages() != null) {
                currentSettings.setAllowCommunityMessages(settings.getAllowCommunityMessages());
            }
            if (settings.getNotifyAnonMessages() != null) {
                currentSettings.setNotifyAnonMessages(settings.getNotifyAnonMessages());
            }
            if (settings.getNotifyAnonDialogClosed() != null) {
                currentSettings.setNotifyAnonDialogClosed(settings.getNotifyAnonDialogClosed());
            }
            if (settings.getNotifyProfileNewChat() != null) {
                currentSettings.setNotifyProfileNewChat(settings.getNotifyProfileNewChat());
            }
            if (settings.getNotifyProfileMessages() != null) {
                currentSettings.setNotifyProfileMessages(settings.getNotifyProfileMessages());
            }
            if (settings.getNotifyProfileDialogClosed() != null) {
                currentSettings.setNotifyProfileDialogClosed(settings.getNotifyProfileDialogClosed());
            }
            if (settings.getNotifySubscriptionProblems() != null) {
                currentSettings.setNotifySubscriptionProblems(settings.getNotifySubscriptionProblems());
            }

            user.setSettings(currentSettings);
        }
        if (profileCost != null) {
            int normalized = Math.max(0, profileCost);
            user.setProfileCost(normalized);
        } else {
            ensureProfileCost(user);
        }
        if (nativeLanguage != null) {
            user.setNativeLanguage(nativeLanguage);
        }

        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public User verifyUser(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        user.setIsVerified(true);
        return userRepository.save(user);
    }

    public Integer getUserBalance(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getBalance();
    }

    public User purchaseCoins(Long userId, Integer amount, String paymentMethod) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        int coinsToAdd = calculateCoinsForPayment(amount, paymentMethod);
        user.setBalance(user.getBalance() + coinsToAdd);

        return userRepository.save(user);
    }

    public void updateUserBalance(Long userId, Integer newBalance) {
        userRepository.updateBalance(userId, newBalance);
    }

    public User updateCommunityNotifications(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getSettings() == null) {
            user.setSettings(new User.UserSettings());
        }
        user.getSettings().setAllowCommunityMessages(enabled);
        return userRepository.save(user);
    }

    public void deductCoins(Long userId, Integer amount) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getBalance() < amount) {
            throw new RuntimeException("Insufficient balance");
        }

        user.setBalance(user.getBalance() - amount);
        userRepository.save(user);
    }

    public void updateOnlineStatus(Long userId, Boolean isOnline) {
        userRepository.updateOnlineStatus(userId, isOnline);
    }

    public User banUser(Long targetUserId, String reason) {
        User user = userRepository.findById(targetUserId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        user.setIsBanned(true);
        user.setBanReason(reason != null ? reason : "Блокировка администрацией");
        user.setBannedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    public User unbanUser(Long targetUserId) {
        User user = userRepository.findById(targetUserId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        user.setIsBanned(false);
        user.setBanReason(null);
        user.setBannedAt(null);
        return userRepository.save(user);
    }

    private User.UserRewards ensureRewards(User user) {
        if (user == null) {
            return null;
        }
        if (user.getRewards() == null) {
            user.setRewards(new User.UserRewards());
        }
        return user.getRewards();
    }

    private Integer calculateAge(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        int age = today.getYear() - birthDate.getYear();
        if (birthDate.plusYears(age).isAfter(today)) {
            age -= 1;
        }
        return Math.max(age, 0);
    }

    private LocalDate parseBirthDate(String birthDate) {
        if (birthDate == null || birthDate.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDate.parse(birthDate.trim());
        } catch (Exception ex) {
            logger.warn("Invalid birthDate provided: {}", birthDate);
            return null;
        }
    }

    private boolean isCommunityMember(Long vkId) {
        if (vkId == null) {
            return false;
        }
        try {
            String url = "https://api.vk.com/method/groups.isMember"
                + "?group_id=" + AppConfig.VK_COMMUNITY_GROUP_ID
                + "&user_id=" + URLEncoder.encode(String.valueOf(vkId), java.nio.charset.StandardCharsets.UTF_8)
                + "&extended=0"
                + "&v=" + AppConfig.VK_API_VERSION
                + "&access_token=" + URLEncoder.encode(AppConfig.VK_COMMUNITY_ACCESS_TOKEN, java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body == null || body.isBlank()) {
                logger.warn("Empty response from VK groups.isMember");
                return false;
            }
            io.vertx.core.json.JsonObject json = new io.vertx.core.json.JsonObject(body);
            if (json.containsKey("error")) {
                logger.warn("VK groups.isMember error: {}", json.getJsonObject("error"));
                return false;
            }
            if (json.containsKey("response")) {
                Object resp = json.getValue("response");
                if (resp instanceof Number) {
                    return ((Number) resp).intValue() == 1;
                }
                if (resp instanceof io.vertx.core.json.JsonObject) {
                    io.vertx.core.json.JsonObject respObj = (io.vertx.core.json.JsonObject) resp;
                    return respObj.getInteger("member", 0) == 1;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to check VK community membership for {}", vkId, e);
        }
        return false;
    }

    private void ensureProfileCost(User user) {
        if (user.getProfileCost() == null || user.getProfileCost() < 0) {
            user.setProfileCost(AppConfig.ANONYMOUS_CHAT_CREATION_COST);
        }
    }

    private void applySpecialPrivileges(User user) {
        if (user == null || user.getVkId() == null) {
            return;
        }

        if (ADMIN_VK_IDS.contains(user.getVkId())) {
            user.setIsVerified(true);
            user.setIsAdmin(true);
            if (user.getBalance() == null || user.getBalance() < 1000) {
                user.setBalance(1000);
            }
        } else if (user.getIsAdmin() == null) {
            user.setIsAdmin(false);
        }
    }

    public List<User> getOnlineUsers() {
        return userRepository.findOnlineUsers();
    }

    public List<User> findUsersForMatching(User.Gender gender, Integer minAge, Integer maxAge, String city) {
        return userRepository.findForMatching(gender, minAge, maxAge, city);
    }

    public UserStats getUserStats(Long userId) {
        return new UserStats(
            0, // totalChats
            0, // activeChats
            0, // totalMessages
            0, // likesReceived
            0, // profileViews
            0  // matchesFound
        );
    }

    public OnlineStats getOnlineStats() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.findOnlineUsers().size();

        return new OnlineStats(
            0, // anonymousChats - будет подсчитываться в ChatService
            (int) totalUsers,
            (int) activeUsers
        );
    }

    public RewardStatus getRewardStatus(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        ensureRewards(user);
        return buildRewardStatus(user);
    }

    public RewardClaimResult claimReward(Long userId, RewardType type, boolean confirmed) {
        if (type == null) {
            throw new RuntimeException("Unknown reward type");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        ensureRewards(user);

        int rewardAmount;
        switch (type) {
            case AD:
                if (!confirmed) {
                    throw new RuntimeException("Ad was not confirmed");
                }
                rewardAmount = AppConfig.AD_REWARD_AMOUNT;
                user.getRewards().setLastAdRewardAt(LocalDateTime.now());
                break;
            case COMMUNITY:
                if (Boolean.TRUE.equals(user.getRewards().getSubscriptionBonusClaimed())) {
                    return new RewardClaimResult(
                        user.getBalance(),
                        0,
                        buildRewardStatus(user)
                    );
                }
                if (user.getVkId() == null) {
                    throw new RuntimeException("VK id is required");
                }
                if (!isCommunityMember(user.getVkId())) {
                    throw new RuntimeException("Community subscription required");
                }
                rewardAmount = AppConfig.SUBSCRIPTION_REWARD_AMOUNT;
                user.getRewards().setSubscriptionBonusClaimed(true);
                break;
            default:
                throw new RuntimeException("Unsupported reward type");
        }

        if (user.getBalance() == null) {
            user.setBalance(0);
        }
        user.setBalance(user.getBalance() + rewardAmount);
        User saved = userRepository.save(user);

        return new RewardClaimResult(
            saved.getBalance(),
            rewardAmount,
            buildRewardStatus(saved)
        );
    }

    private RewardStatus buildRewardStatus(User user) {
        ensureRewards(user);
        boolean subscriptionClaimed = user.getRewards() != null
            && Boolean.TRUE.equals(user.getRewards().getSubscriptionBonusClaimed());

        return new RewardStatus(
            true,
            null,
            !subscriptionClaimed,
            subscriptionClaimed
        );
    }

    public enum RewardType {
        AD,
        COMMUNITY
    }

    public static class RewardStatus {
        private final boolean adAvailable;
        private final Integer adCooldownSeconds;
        private final boolean subscriptionAvailable;
        private final boolean subscriptionClaimed;

        public RewardStatus(
            boolean adAvailable,
            Integer adCooldownSeconds,
            boolean subscriptionAvailable,
            boolean subscriptionClaimed
        ) {
            this.adAvailable = adAvailable;
            this.adCooldownSeconds = adCooldownSeconds;
            this.subscriptionAvailable = subscriptionAvailable;
            this.subscriptionClaimed = subscriptionClaimed;
        }

        public boolean isAdAvailable() { return adAvailable; }
        public Integer getAdCooldownSeconds() { return adCooldownSeconds; }
        public boolean isSubscriptionAvailable() { return subscriptionAvailable; }
        public boolean isSubscriptionClaimed() { return subscriptionClaimed; }
    }

    public static class RewardClaimResult {
        private final int balance;
        private final int rewardedAmount;
        private final RewardStatus rewards;

        public RewardClaimResult(int balance, int rewardedAmount, RewardStatus rewards) {
            this.balance = balance;
            this.rewardedAmount = rewardedAmount;
            this.rewards = rewards;
        }

        public int getBalance() { return balance; }
        public int getRewardedAmount() { return rewardedAmount; }
        public RewardStatus getRewards() { return rewards; }
    }

    private int calculateCoinsForPayment(Integer amount, String paymentMethod) {
        switch (paymentMethod) {
            case "vk_pay":
                return amount * AppConfig.VK_PAY_COIN_RATE; // 1 рубль = 100 фиан
            case "votes":
                return amount * AppConfig.VOTES_COIN_RATE;  // 1 голос = 10 фиан
            default:
                return amount;
        }
    }

    public static class UserStats {
        private final int totalChats;
        private final int activeChats;
        private final int totalMessages;
        private final int likesReceived;
        private final int profileViews;
        private final int matchesFound;

        public UserStats(int totalChats, int activeChats, int totalMessages,
                        int likesReceived, int profileViews, int matchesFound) {
            this.totalChats = totalChats;
            this.activeChats = activeChats;
            this.totalMessages = totalMessages;
            this.likesReceived = likesReceived;
            this.profileViews = profileViews;
            this.matchesFound = matchesFound;
        }

        public int getTotalChats() { return totalChats; }
        public int getActiveChats() { return activeChats; }
        public int getTotalMessages() { return totalMessages; }
        public int getLikesReceived() { return likesReceived; }
        public int getProfileViews() { return profileViews; }
        public int getMatchesFound() { return matchesFound; }
    }

    public static class OnlineStats {
        private final int anonymousChats;
        private final int totalUsers;
        private final int activeUsers;

        public OnlineStats(int anonymousChats, int totalUsers, int activeUsers) {
            this.anonymousChats = anonymousChats;
            this.totalUsers = totalUsers;
            this.activeUsers = activeUsers;
        }

        public int getAnonymousChats() { return anonymousChats; }
        public int getTotalUsers() { return totalUsers; }
        public int getActiveUsers() { return activeUsers; }
    }
}
