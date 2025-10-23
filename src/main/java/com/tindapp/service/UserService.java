package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createOrUpdateUser(Long vkId) {
        Optional<User> existingUser = userRepository.findByVkId(vkId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.updateLastSeen();
            return userRepository.save(user);
        } else {
            User newUser = new User(vkId);
            newUser.setBalance(AppConfig.INITIAL_USER_BALANCE); // начальный баланс
            return userRepository.save(newUser);
        }
    }

    public User getOrCreateUser(Long vkId) {
        Optional<User> existingUser = userRepository.findByVkId(vkId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.updateLastSeen();
            return userRepository.save(user);
        } else {
            User newUser = new User(vkId);
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
        return userRepository.save(user);
    }

    public User updateUser(User user) {
        user.setUpdatedAtDateTime(LocalDateTime.now());
        return userRepository.save(user);
    }

    public User updateProfile(Long userId, String bio, String city, Integer age, String birthDate, Boolean isVisible, User.UserSettings settings) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (bio != null) {
            user.setBio(bio);
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
            user.setSettings(settings);
        }

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
