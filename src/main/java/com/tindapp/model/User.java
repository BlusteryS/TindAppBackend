package com.tindapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tindapp.config.AppConfig;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class User {

    private Long id;
    private Long vkId;
    private Integer age;
    private String firstName;
    private String lastName;
    private String avatarUrl;
    private String country;
    private String city;
    private Boolean isVerified;
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

    public User() {
        this.isVerified = false;
        this.isOnline = false;
        this.isVisible = true;
        this.balance = 0;
        this.subscription = new UserSubscription();
        this.settings = new UserSettings();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.firstName = "";
        this.lastName = "";
        this.avatarUrl = "";
        this.profileCost = AppConfig.ANONYMOUS_CHAT_CREATION_COST;
        this.isAdmin = false;
    }

    public User(Long vkId) {
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
            this.isActive = false;
        }

        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }

        public LocalDateTime getExpiresAt() { return expiresAt; }
        public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

        public SubscriptionType getType() { return type; }
        public void setType(SubscriptionType type) { this.type = type; }
    }

    public static class UserSettings {
        private Boolean showAge;
        private Boolean showCity;
        private Boolean allowMessages;

        public UserSettings() {
            this.showAge = true;
            this.showCity = true;
            this.allowMessages = true;
        }

        public Boolean getShowAge() { return showAge; }
        public void setShowAge(Boolean showAge) { this.showAge = showAge; }

        public Boolean getShowCity() { return showCity; }
        public void setShowCity(Boolean showCity) { this.showCity = showCity; }

        public Boolean getAllowMessages() { return allowMessages; }
        public void setAllowMessages(Boolean allowMessages) { this.allowMessages = allowMessages; }
    }

    public enum SubscriptionType {
        BASIC, PREMIUM
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVkId() { return vkId; }
    public void setVkId(Long vkId) { this.vkId = vkId; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public Boolean getIsVerified() { return isVerified; }
    public void setIsVerified(Boolean isVerified) { this.isVerified = isVerified; }

    public boolean isVerified() { return isVerified != null ? isVerified : false; }
    public void setVerified(boolean verified) { this.isVerified = verified; }

    public Boolean getIsOnline() { return isOnline; }
    public void setIsOnline(Boolean isOnline) { this.isOnline = isOnline; }

    public boolean isOnline() { return isOnline != null ? isOnline : false; }
    public void setOnline(boolean online) { this.isOnline = online; }

    public LocalDateTime getLastSeenDateTime() { return lastSeen; }
    public void setLastSeenDateTime(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public Gender getGenderEnum() { return gender; }
    public void setGenderEnum(Gender gender) { this.gender = gender; }

    public Boolean getIsVisible() { return isVisible; }
    public void setIsVisible(Boolean isVisible) { this.isVisible = isVisible; }

    public boolean isVisible() { return isVisible != null ? isVisible : true; }
    public void setVisible(boolean visible) { this.isVisible = visible; }

    public UserSubscription getSubscription() { return subscription; }
    public void setSubscription(UserSubscription subscription) { this.subscription = subscription; }

    public Integer getBalance() { return balance; }
    public void setBalance(Integer balance) { this.balance = balance; }

    public UserSettings getSettings() { return settings; }
    public void setSettings(UserSettings settings) { this.settings = settings; }

    public Integer getProfileCost() { return profileCost; }
    public void setProfileCost(Integer profileCost) { this.profileCost = profileCost; }

    public Boolean getIsAdmin() { return isAdmin; }
    public void setIsAdmin(Boolean isAdmin) { this.isAdmin = isAdmin; }
    public boolean isAdmin() { return isAdmin != null ? isAdmin : false; }

    public LocalDateTime getCreatedAtDateTime() { return createdAt; }
    public void setCreatedAtDateTime(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAtDateTime() { return updatedAt; }
    public void setUpdatedAtDateTime(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public void updateLastSeen() {
        this.lastSeen = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateOnlineStatus(Boolean isOnline) {
        this.isOnline = isOnline;
        if (!isOnline) {
            this.lastSeen = LocalDateTime.now();
        }
        this.updatedAt = LocalDateTime.now();
    }

    public String getLastSeen() {
        return lastSeen != null ? lastSeen.toString() : null;
    }

    public void setLastSeen(String lastSeen) {
        if (lastSeen != null) {
            this.lastSeen = LocalDateTime.parse(lastSeen);
        }
    }

    public String getCreatedAt() {
        return createdAt != null ? createdAt.toString() : null;
    }

    public void setCreatedAt(String createdAt) {
        if (createdAt != null) {
            this.createdAt = LocalDateTime.parse(createdAt);
        }
    }

    public String getUpdatedAt() {
        return updatedAt != null ? updatedAt.toString() : null;
    }

    public void setUpdatedAt(String updatedAt) {
        if (updatedAt != null) {
            this.updatedAt = LocalDateTime.parse(updatedAt);
        }
    }

    public String getGender() {
        return gender != null ? gender.toString().toLowerCase() : "other";
    }

    public void setGender(String gender) {
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
