package com.tindapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tindapp.util.DateTimeUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Chat {

    private String id;
    private ChatType type;
    private Long user1Id;
    private Long user2Id;
    private Message lastMessage;
    private Integer unreadCount;
    private String createdAt;
    private String updatedAt;
    private Boolean isActive;
    private Long closedByUserId;
    private ChatClosureReason closureReason;
    private String closedAt;
    private ChatSettings settings;

    public Chat() {
        unreadCount = 0;
        isActive = true;
        settings = new ChatSettings();
        closedByUserId = null;
        closureReason = null;
        closedAt = null;
        createdAt = DateTimeUtils.nowAsIso();
        updatedAt = DateTimeUtils.nowAsIso();
    }

    public Chat(final String id, final ChatType type, final Long user1Id, final Long user2Id) {
        this();
        this.id = id;
        this.type = type;
        this.user1Id = user1Id;
        this.user2Id = user2Id;
    }

    public enum ChatType {
        ANONYMOUS, REGULAR
    }

    public enum ChatClosureReason {
        MANUAL,
        BLOCKED,
        SYSTEM
    }

    public static class ChatSettings {
        private Integer cost; // стоимость создания чата в фианах
        private Integer anonymousId; // Порядковый номер для анонимного чата

        public ChatSettings() {
            cost = 0;
        }

        public Integer getCost() {
            return cost;
        }

        public void setCost(final Integer cost) {
            this.cost = cost;
        }

        public Integer getAnonymousId() {
            return anonymousId;
        }

        public void setAnonymousId(final Integer anonymousId) {
            this.anonymousId = anonymousId;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public ChatType getType() {
        return type;
    }

    public void setType(final ChatType type) {
        this.type = type;
    }

    public Long getUser1Id() {
        return user1Id;
    }

    public void setUser1Id(final Long user1Id) {
        this.user1Id = user1Id;
    }

    public Long getUser2Id() {
        return user2Id;
    }

    public void setUser2Id(final Long user2Id) {
        this.user2Id = user2Id;
    }

    public Message getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(final Message lastMessage) {
        this.lastMessage = lastMessage;
    }

    public Integer getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(final Integer unreadCount) {
        this.unreadCount = unreadCount;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(final Boolean isActive) {
        this.isActive = isActive;
    }

    public Long getClosedByUserId() {
        return closedByUserId;
    }

    public void setClosedByUserId(final Long closedByUserId) {
        this.closedByUserId = closedByUserId;
    }

    public ChatClosureReason getClosureReason() {
        return closureReason;
    }

    public void setClosureReason(final ChatClosureReason closureReason) {
        this.closureReason = closureReason;
    }

    public String getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(final String closedAt) {
        this.closedAt = closedAt;
    }

    public ChatSettings getSettings() {
        return settings;
    }

    public void setSettings(final ChatSettings settings) {
        this.settings = settings;
    }

    public boolean hasParticipant(final Long userId) {
        return userId.equals(user1Id) || userId.equals(user2Id);
    }

    public Long getCompanionId(final Long userId) {
        if (userId.equals(user1Id)) {
            return user2Id;
        } else if (userId.equals(user2Id)) {
            return user1Id;
        }
        return null;
    }

    public void updateActivity() {
        updatedAt = DateTimeUtils.nowAsIso();
    }

    public void resetUnreadCount() {
        unreadCount = 0;
    }
}
