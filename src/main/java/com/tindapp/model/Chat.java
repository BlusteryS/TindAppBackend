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
    private ChatSettings settings;

    public Chat() {
        this.unreadCount = 0;
        this.isActive = true;
        this.settings = new ChatSettings();
        this.createdAt = DateTimeUtils.nowAsIso();
        this.updatedAt = DateTimeUtils.nowAsIso();
    }

    public Chat(String id, ChatType type, Long user1Id, Long user2Id) {
        this();
        this.id = id;
        this.type = type;
        this.user1Id = user1Id;
        this.user2Id = user2Id;
    }

    public enum ChatType {
        ANONYMOUS, REGULAR
    }

    public static class ChatSettings {
        private Integer cost; // стоимость создания чата в фишках
        private Integer anonymousId; // Порядковый номер для анонимного чата

        public ChatSettings() {
            this.cost = 0;
        }

        public Integer getCost() { return cost; }
        public void setCost(Integer cost) { this.cost = cost; }

        public Integer getAnonymousId() { return anonymousId; }
        public void setAnonymousId(Integer anonymousId) { this.anonymousId = anonymousId; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ChatType getType() { return type; }
    public void setType(ChatType type) { this.type = type; }

    public Long getUser1Id() { return user1Id; }
    public void setUser1Id(Long user1Id) { this.user1Id = user1Id; }

    public Long getUser2Id() { return user2Id; }
    public void setUser2Id(Long user2Id) { this.user2Id = user2Id; }

    public Message getLastMessage() { return lastMessage; }
    public void setLastMessage(Message lastMessage) { this.lastMessage = lastMessage; }

    public Integer getUnreadCount() { return unreadCount; }
    public void setUnreadCount(Integer unreadCount) { this.unreadCount = unreadCount; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public ChatSettings getSettings() { return settings; }
    public void setSettings(ChatSettings settings) { this.settings = settings; }

    public boolean hasParticipant(Long userId) {
        return userId.equals(user1Id) || userId.equals(user2Id);
    }

    public Long getCompanionId(Long userId) {
        if (userId.equals(user1Id)) {
            return user2Id;
        } else if (userId.equals(user2Id)) {
            return user1Id;
        }
        return null;
    }

    public void updateActivity() {
        this.updatedAt = DateTimeUtils.nowAsIso();
    }

    public void resetUnreadCount() {
        this.unreadCount = 0;
    }
}
