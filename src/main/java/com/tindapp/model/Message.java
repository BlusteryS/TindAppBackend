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
    private String clientMessageId;
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
        isRead = false;
        isEdited = false;
        type = MessageType.TEXT;
        createdAt = DateTimeUtils.nowAsIso();
        updatedAt = DateTimeUtils.nowAsIso();
        translations = new HashMap<>();
    }

    public Message(final String id, final String chatId, final Long senderId, final String text) {
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

        public ReplyInfo() {
        }

        public ReplyInfo(final String messageId, final String text, final String senderName) {
            this.messageId = messageId;
            this.text = text;
            this.senderName = senderName;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(final String messageId) {
            this.messageId = messageId;
        }

        public String getText() {
            return text;
        }

        public void setText(final String text) {
            this.text = text;
        }

        public String getSenderName() {
            return senderName;
        }

        public void setSenderName(final String senderName) {
            this.senderName = senderName;
        }
    }

    public static class MessageAttachment {
        private AttachmentType type;
        private String url;
        private String preview;

        public MessageAttachment() {
        }

        public MessageAttachment(final AttachmentType type, final String url) {
            this.type = type;
            this.url = url;
        }

        public MessageAttachment(final AttachmentType type, final String url, final String preview) {
            this(type, url);
            this.preview = preview;
        }

        public enum AttachmentType {
            IMAGE, STICKER
        }

        public AttachmentType getType() {
            return type;
        }

        public void setType(final AttachmentType type) {
            this.type = type;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(final String url) {
            this.url = url;
        }

        public String getPreview() {
            return preview;
        }

        public void setPreview(final String preview) {
            this.preview = preview;
        }
    }

    public static class MessageTranslation {
        private String to;
        private String from;
        private String text;

        public MessageTranslation() {
        }

        public MessageTranslation(final String to, final String from, final String text) {
            this.to = to;
            this.from = from;
            this.text = text;
        }

        public String getTo() {
            return to;
        }

        public void setTo(final String to) {
            this.to = to;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(final String from) {
            this.from = from;
        }

        public String getText() {
            return text;
        }

        public void setText(final String text) {
            this.text = text;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(final String chatId) {
        this.chatId = chatId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(final Long senderId) {
        this.senderId = senderId;
    }

    public String getText() {
        return text;
    }

    public void setText(final String text) {
        this.text = text;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public void setClientMessageId(final String clientMessageId) {
        this.clientMessageId = clientMessageId;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(final MessageType type) {
        this.type = type;
    }

    public ReplyInfo getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(final ReplyInfo replyTo) {
        this.replyTo = replyTo;
    }

    public List<MessageAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(final List<MessageAttachment> attachments) {
        this.attachments = attachments;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(final Boolean isRead) {
        this.isRead = isRead;
    }

    public Boolean getIsEdited() {
        return isEdited;
    }

    public void setIsEdited(final Boolean isEdited) {
        this.isEdited = isEdited;
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

    public Map<String, MessageTranslation> getTranslations() {
        return translations;
    }

    public void setTranslations(final Map<String, MessageTranslation> translations) {
        this.translations = translations;
    }

    public void addTranslation(final MessageTranslation translation) {
        if (translation == null || translation.getTo() == null) {
            return;
        }
        if (translations == null) {
            translations = new HashMap<>();
        }
        translations.put(translation.getTo().toLowerCase(), translation);
    }

    public void clearTranslations() {
        if (translations != null) {
            translations.clear();
        }
    }

    public void markAsRead() {
        isRead = true;
        updatedAt = DateTimeUtils.nowAsIso();
    }

    public void markAsEdited() {
        isEdited = true;
        updatedAt = DateTimeUtils.nowAsIso();
    }

    public void updateText(final String newText) {
        text = newText;
        markAsEdited();
    }
}
