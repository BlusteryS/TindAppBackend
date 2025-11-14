package com.tindapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Report {

    private String id;
    private Long reporterId;
    private Long targetId;
    private String chatId;
    private String messageId;
    private ReportReason reason;
    private String description;
    private ReportStatus status;
    private LocalDateTime createdAt;

    public Report() {
        this.status = ReportStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public Report(String id, Long reporterId, Long targetId, ReportReason reason) {
        this();
        this.id = id;
        this.reporterId = reporterId;
        this.targetId = targetId;
        this.reason = reason;
    }

    public enum ReportReason {
        SPAM,
        INAPPROPRIATE,
        FAKE,
        HARASSMENT,
        OTHER,
        UNDER18,
        VIOLENCE,
        ABUSE,
        INSULTS,
        RULES
    }

    public enum ReportStatus {
        PENDING, REVIEWED, RESOLVED, DISMISSED
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getReporterId() { return reporterId; }
    public void setReporterId(Long reporterId) { this.reporterId = reporterId; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public ReportReason getReason() { return reason; }
    public void setReason(ReportReason reason) { this.reason = reason; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ReportStatus getStatus() { return status; }
    public void setStatus(ReportStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public void resolve() {
        this.status = ReportStatus.RESOLVED;
    }

    public void dismiss() {
        this.status = ReportStatus.DISMISSED;
    }

    public void review() {
        this.status = ReportStatus.REVIEWED;
    }
}
