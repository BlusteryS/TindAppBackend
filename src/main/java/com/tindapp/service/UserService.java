package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createOrUpdateUser(Long vkId) {
        Optional<User> existingUser = userRepository.findByVkId(vkId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.updateLastSeen();
            ensureProfileCost(user);
            applySpecialPrivileges(user);
            return userRepository.save(user);
        } else {
            User newUser = new User(vkId);
            newUser.setBalance(AppConfig.INITIAL_USER_BALANCE); // начальный баланс
            ensureProfileCost(newUser);
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
            applySpecialPrivileges(user);
            return userRepository.save(user);
        } else {
            User newUser = new User(vkId);
            ensureProfileCost(newUser);
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
        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public User updateUser(User user) {
        user.setUpdatedAtDateTime(LocalDateTime.now());
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
        Integer profileCost
    ) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

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
        if (age != null) {
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
