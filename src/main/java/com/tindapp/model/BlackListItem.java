package com.tindapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class BlackListItem {

    private String id;
    private Long userId;
    private Long blockedUserId;
    private String reason;
    private LocalDateTime createdAt;

    public BlackListItem() {
        this.createdAt = LocalDateTime.now();
    }

    public BlackListItem(String id, Long userId, Long blockedUserId) {
        this();
        this.id = id;
        this.userId = userId;
        this.blockedUserId = blockedUserId;
    }

    public BlackListItem(String id, Long userId, Long blockedUserId, String reason) {
        this(id, userId, blockedUserId);
        this.reason = reason;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getBlockedUserId() { return blockedUserId; }
    public void setBlockedUserId(Long blockedUserId) { this.blockedUserId = blockedUserId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
