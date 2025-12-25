package com.tindapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tindapp.config.AppConfig;
import com.tindapp.util.LanguageUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class User {

    private Long id;
    private Long vkId;
    private Integer age;
    private LocalDate birthDate;
    private String firstName;
    private String lastName;
    private String avatarUrl;
    private String country;
    private String city;
    private Boolean isVerified;
    private Boolean wasVerified;
    private Boolean isOnline;
    private LocalDateTime lastSeen;
    private String bio;
    private Gender gender;
    private Boolean isVisible;
    private UserSubscription subscription;
    private Integer balance;
    private UserSettings settings;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer profileCost;
    private Boolean isAdmin;
    private Boolean isBanned;
    private String banReason;
    private LocalDateTime bannedAt;
    private String nativeLanguage;
    private UserRewards rewards;

    public User() {
        isVerified = false;
        wasVerified = false;
        isOnline = false;
        isVisible = true;
        balance = 0;
        subscription = new UserSubscription();
        settings = new UserSettings();
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        firstName = "";
        lastName = "";
        avatarUrl = "";
        profileCost = AppConfig.ANONYMOUS_CHAT_CREATION_COST;
        isAdmin = false;
        isBanned = false;
        nativeLanguage = LanguageUtils.getDefaultLanguage();
        rewards = new UserRewards();
    }

    public User(final Long vkId) {
        this();
        this.vkId = vkId;
    }

    public enum Gender {
        MALE, FEMALE, OTHER
    }

    public static class UserSubscription {
        private Boolean isActive;
        private LocalDateTime expiresAt;
        private SubscriptionType type;

        public UserSubscription() {
            isActive = false;
        }

        public Boolean getIsActive() {
            return isActive;
        }

        public void setIsActive(final Boolean isActive) {
            this.isActive = isActive;
        }

        public LocalDateTime getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(final LocalDateTime expiresAt) {
            this.expiresAt = expiresAt;
        }

        public SubscriptionType getType() {
            return type;
        }

        public void setType(final SubscriptionType type) {
            this.type = type;
        }
    }

    public static class UserSettings {
        private Boolean showAge;
        private Boolean showCity;
        private Boolean allowMessages;
        private Boolean allowCommunityMessages;
        private Boolean notifyAnonMessages;
        private Boolean notifyAnonDialogClosed;
        private Boolean notifyProfileNewChat;
        private Boolean notifyProfileMessages;
        private Boolean notifyProfileDialogClosed;
        private Boolean notifySubscriptionProblems;

        public UserSettings() {
            this(true);
        }

        public UserSettings(final boolean applyDefaults) {
            if (applyDefaults) {
                showAge = true;
                showCity = true;
                allowMessages = true;
                allowCommunityMessages = false;
                notifyAnonMessages = true;
                notifyAnonDialogClosed = true;
                notifyProfileNewChat = true;
                notifyProfileMessages = true;
                notifyProfileDialogClosed = true;
                notifySubscriptionProblems = true;
            }
        }

        public Boolean getShowAge() {
            return showAge;
        }

        public void setShowAge(final Boolean showAge) {
            this.showAge = showAge;
        }

        public Boolean getShowCity() {
            return showCity;
        }

        public void setShowCity(final Boolean showCity) {
            this.showCity = showCity;
        }

        public Boolean getAllowMessages() {
            return allowMessages;
        }

        public void setAllowMessages(final Boolean allowMessages) {
            this.allowMessages = allowMessages;
        }

        public Boolean getAllowCommunityMessages() {
            return allowCommunityMessages;
        }

        public void setAllowCommunityMessages(final Boolean allowCommunityMessages) {
            this.allowCommunityMessages = allowCommunityMessages;
        }

        public Boolean getNotifyAnonMessages() {
            return notifyAnonMessages;
        }

        public void setNotifyAnonMessages(final Boolean notifyAnonMessages) {
            this.notifyAnonMessages = notifyAnonMessages;
        }

        public Boolean getNotifyAnonDialogClosed() {
            return notifyAnonDialogClosed;
        }

        public void setNotifyAnonDialogClosed(final Boolean notifyAnonDialogClosed) {
            this.notifyAnonDialogClosed = notifyAnonDialogClosed;
        }

        public Boolean getNotifyProfileNewChat() {
            return notifyProfileNewChat;
        }

        public void setNotifyProfileNewChat(final Boolean notifyProfileNewChat) {
            this.notifyProfileNewChat = notifyProfileNewChat;
        }

        public Boolean getNotifyProfileMessages() {
            return notifyProfileMessages;
        }

        public void setNotifyProfileMessages(final Boolean notifyProfileMessages) {
            this.notifyProfileMessages = notifyProfileMessages;
        }

        public Boolean getNotifyProfileDialogClosed() {
            return notifyProfileDialogClosed;
        }

        public void setNotifyProfileDialogClosed(final Boolean notifyProfileDialogClosed) {
            this.notifyProfileDialogClosed = notifyProfileDialogClosed;
        }

        public Boolean getNotifySubscriptionProblems() {
            return notifySubscriptionProblems;
        }

        public void setNotifySubscriptionProblems(final Boolean notifySubscriptionProblems) {
            this.notifySubscriptionProblems = notifySubscriptionProblems;
        }
    }

    public static class UserRewards {
        private Boolean subscriptionBonusClaimed;
        private LocalDateTime lastAdRewardAt;

        public UserRewards() {
            subscriptionBonusClaimed = false;
        }

        public Boolean getSubscriptionBonusClaimed() {
            return subscriptionBonusClaimed;
        }

        public void setSubscriptionBonusClaimed(final Boolean subscriptionBonusClaimed) {
            this.subscriptionBonusClaimed = subscriptionBonusClaimed;
        }

        public LocalDateTime getLastAdRewardAt() {
            return lastAdRewardAt;
        }

        public void setLastAdRewardAt(final LocalDateTime lastAdRewardAt) {
            this.lastAdRewardAt = lastAdRewardAt;
        }
    }

    public enum SubscriptionType {
        BASIC, PREMIUM
    }

    public Long getId() {
        return id;
    }

    public void setId(final Long id) {
        this.id = id;
    }

    public Long getVkId() {
        return vkId;
    }

    public void setVkId(final Long vkId) {
        this.vkId = vkId;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(final Integer age) {
        this.age = age;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(final LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(final String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(final String lastName) {
        this.lastName = lastName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(final String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(final String country) {
        this.country = country;
    }

    public String getCity() {
        return city;
    }

    public void setCity(final String city) {
        this.city = city;
    }

    public Boolean getIsVerified() {
        return isVerified;
    }

    public void setIsVerified(final Boolean isVerified) {
        this.isVerified = isVerified;
    }

    public Boolean getWasVerified() {
        return wasVerified;
    }

    public void setWasVerified(final Boolean wasVerified) {
        this.wasVerified = wasVerified;
    }

    public boolean isVerified() {
        return isVerified != null ? isVerified : false;
    }

    public void setVerified(final boolean verified) {
        isVerified = verified;
    }

    public boolean wasVerified() {
        return wasVerified != null ? wasVerified : false;
    }

    public Boolean getIsOnline() {
        return isOnline;
    }

    public void setIsOnline(final Boolean isOnline) {
        this.isOnline = isOnline;
    }

    public boolean isOnline() {
        return isOnline != null ? isOnline : false;
    }

    public void setOnline(final boolean online) {
        isOnline = online;
    }

    public LocalDateTime getLastSeenDateTime() {
        return lastSeen;
    }

    public void setLastSeenDateTime(final LocalDateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(final String bio) {
        this.bio = bio;
    }

    public Gender getGenderEnum() {
        return gender;
    }

    public void setGenderEnum(final Gender gender) {
        this.gender = gender;
    }

    public Boolean getIsVisible() {
        return isVisible;
    }

    public void setIsVisible(final Boolean isVisible) {
        this.isVisible = isVisible;
    }

    public boolean isVisible() {
        return isVisible != null ? isVisible : true;
    }

    public void setVisible(final boolean visible) {
        isVisible = visible;
    }

    public UserSubscription getSubscription() {
        return subscription;
    }

    public void setSubscription(final UserSubscription subscription) {
        this.subscription = subscription;
    }

    public Integer getBalance() {
        return balance;
    }

    public void setBalance(final Integer balance) {
        this.balance = balance;
    }

    public UserSettings getSettings() {
        return settings;
    }

    public void setSettings(final UserSettings settings) {
        this.settings = settings;
    }

    public Integer getProfileCost() {
        return profileCost;
    }

    public void setProfileCost(final Integer profileCost) {
        this.profileCost = profileCost;
    }

    public Boolean getIsAdmin() {
        return isAdmin;
    }

    public void setIsAdmin(final Boolean isAdmin) {
        this.isAdmin = isAdmin;
    }

    public boolean isAdmin() {
        return isAdmin != null ? isAdmin : false;
    }

    public Boolean getIsBanned() {
        return isBanned;
    }

    public void setIsBanned(final Boolean isBanned) {
        this.isBanned = isBanned;
    }

    public boolean isBanned() {
        return isBanned != null ? isBanned : false;
    }

    public String getBanReason() {
        return banReason;
    }

    public void setBanReason(final String banReason) {
        this.banReason = banReason;
    }

    public LocalDateTime getBannedAt() {
        return bannedAt;
    }

    public void setBannedAt(final LocalDateTime bannedAt) {
        this.bannedAt = bannedAt;
    }

    public String getNativeLanguage() {
        return nativeLanguage;
    }

    public void setNativeLanguage(final String nativeLanguage) {
        this.nativeLanguage = LanguageUtils.normalizeLanguage(nativeLanguage);
    }

    public UserRewards getRewards() {
        return rewards;
    }

    public void setRewards(final UserRewards rewards) {
        this.rewards = rewards;
    }

    public LocalDateTime getCreatedAtDateTime() {
        return createdAt;
    }

    public void setCreatedAtDateTime(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAtDateTime() {
        return updatedAt;
    }

    public void setUpdatedAtDateTime(final LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void updateLastSeen() {
        lastSeen = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    public void updateOnlineStatus(final Boolean isOnline) {
        this.isOnline = isOnline;
        if (!isOnline) {
            lastSeen = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    public String getLastSeen() {
        return lastSeen != null ? lastSeen.toString() : null;
    }

    public void setLastSeen(final String lastSeen) {
        if (lastSeen != null) {
            this.lastSeen = LocalDateTime.parse(lastSeen);
        }
    }

    public String getCreatedAt() {
        return createdAt != null ? createdAt.toString() : null;
    }

    public void setCreatedAt(final String createdAt) {
        if (createdAt != null) {
            this.createdAt = LocalDateTime.parse(createdAt);
        }
    }

    public String getUpdatedAt() {
        return updatedAt != null ? updatedAt.toString() : null;
    }

    public void setUpdatedAt(final String updatedAt) {
        if (updatedAt != null) {
            this.updatedAt = LocalDateTime.parse(updatedAt);
        }
    }

    public String getGender() {
        return gender != null ? gender.toString().toLowerCase() : "other";
    }

    public void setGender(final String gender) {
        if (gender != null) {
            switch (gender.toLowerCase()) {
                case "male":
                    this.gender = Gender.MALE;
                    break;
                case "female":
                    this.gender = Gender.FEMALE;
                    break;
                default:
                    this.gender = Gender.OTHER;
                    break;
            }
        }
    }
}
