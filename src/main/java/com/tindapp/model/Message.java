package com.tindapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.tindapp.util.DateTimeUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Message {

    private String id;
    private String chatId;
    private Long senderId;
    private String text;
    private MessageType type;
    private ReplyInfo replyTo;
    private List<MessageAttachment> attachments;
    private Boolean isRead;
    private Boolean isEdited;
    private String createdAt;
    private String updatedAt;
    private Map<String, MessageTranslation> translations;

    public Message() {
        this.isRead = false;
        this.isEdited = false;
        this.type = MessageType.TEXT;
        this.createdAt = DateTimeUtils.nowAsIso();
        this.updatedAt = DateTimeUtils.nowAsIso();
        this.translations = new HashMap<>();
    }

    public Message(String id, String chatId, Long senderId, String text) {
        this();
        this.id = id;
        this.chatId = chatId;
        this.senderId = senderId;
        this.text = text;
    }

    public enum MessageType {
        TEXT, SYSTEM, IMAGE, STICKER
    }

    public static class ReplyInfo {
        private String messageId;
        private String text;
        private String senderName;

        public ReplyInfo() {}

        public ReplyInfo(String messageId, String text, String senderName) {
            this.messageId = messageId;
            this.text = text;
            this.senderName = senderName;
        }

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public String getSenderName() { return senderName; }
        public void setSenderName(String senderName) { this.senderName = senderName; }
    }

    public static class MessageAttachment {
        private AttachmentType type;
        private String url;
        private String preview;

        public MessageAttachment() {}

        public MessageAttachment(AttachmentType type, String url) {
            this.type = type;
            this.url = url;
        }

        public MessageAttachment(AttachmentType type, String url, String preview) {
            this(type, url);
            this.preview = preview;
        }

        public enum AttachmentType {
            IMAGE, STICKER
        }

        public AttachmentType getType() { return type; }
        public void setType(AttachmentType type) { this.type = type; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getPreview() { return preview; }
        public void setPreview(String preview) { this.preview = preview; }
    }

    public static class MessageTranslation {
        private String to;
        private String from;
        private String text;

        public MessageTranslation() {}

        public MessageTranslation(String to, String from, String text) {
            this.to = to;
            this.from = from;
            this.text = text;
        }

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public Long getSenderId() { return senderId; }
    public void setSenderId(Long senderId) { this.senderId = senderId; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public ReplyInfo getReplyTo() { return replyTo; }
    public void setReplyTo(ReplyInfo replyTo) { this.replyTo = replyTo; }

    public List<MessageAttachment> getAttachments() { return attachments; }
    public void setAttachments(List<MessageAttachment> attachments) { this.attachments = attachments; }

    public Boolean getIsRead() { return isRead; }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }

    public Boolean getIsEdited() { return isEdited; }
    public void setIsEdited(Boolean isEdited) { this.isEdited = isEdited; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public Map<String, MessageTranslation> getTranslations() { return translations; }
    public void setTranslations(Map<String, MessageTranslation> translations) { this.translations = translations; }

    public void addTranslation(MessageTranslation translation) {
        if (translation == null || translation.getTo() == null) {
            return;
        }
        if (this.translations == null) {
            this.translations = new HashMap<>();
        }
        this.translations.put(translation.getTo().toLowerCase(), translation);
    }

    public void clearTranslations() {
        if (this.translations != null) {
            this.translations.clear();
        }
    }

    public void markAsRead() {
        this.isRead = true;
        this.updatedAt = DateTimeUtils.nowAsIso();
    }

    public void markAsEdited() {
        this.isEdited = true;
        this.updatedAt = DateTimeUtils.nowAsIso();
    }

    public void updateText(String newText) {
        this.text = newText;
        this.markAsEdited();
    }
}
