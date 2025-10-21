package com.tindapp.util;

import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.model.User;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ResponseMapper {

    private ResponseMapper() {
    }

    public static JsonObject toUserResponse(User user) {
        if (user == null) {
            return new JsonObject();
        }

        JsonObject response = new JsonObject()
            .put("id", user.getId())
            .put("vkId", user.getVkId())
            .put("age", user.getAge())
            .put("city", user.getCity())
            .put("isVerified", user.isVerified())
            .put("isOnline", user.isOnline())
            .put("bio", user.getBio())
            .put("gender", user.getGender())
            .put("isVisible", user.isVisible())
            .put("balance", user.getBalance());

        response.put("lastSeen", DateTimeUtils.formatToIso(user.getLastSeenDateTime()));
        response.put("createdAt", DateTimeUtils.formatToIso(user.getCreatedAtDateTime()));
        response.put("updatedAt", DateTimeUtils.formatToIso(user.getUpdatedAtDateTime()));

        JsonObject subscription = new JsonObject();
        if (user.getSubscription() != null) {
            subscription
                .put("isActive", user.getSubscription().getIsActive())
                .put("type", user.getSubscription().getType() != null ? user.getSubscription().getType().name().toLowerCase() : null);
            if (user.getSubscription().getExpiresAt() != null) {
                subscription.put("expiresAt", DateTimeUtils.formatToIso(user.getSubscription().getExpiresAt()));
            }
        } else {
            subscription
                .put("isActive", false)
                .put("type", null);
        }
        response.put("subscription", subscription);

        JsonObject settings = new JsonObject();
        if (user.getSettings() != null) {
            settings
                .put("showAge", user.getSettings().getShowAge())
                .put("showCity", user.getSettings().getShowCity())
                .put("allowMessages", user.getSettings().getAllowMessages());
        } else {
            settings
                .put("showAge", true)
                .put("showCity", true)
                .put("allowMessages", true);
        }
        response.put("settings", settings);

        return response;
    }

    public static JsonObject toMessageResponse(Message message) {
        if (message == null) {
            return new JsonObject();
        }

        JsonObject response = new JsonObject()
            .put("id", message.getId())
            .put("chatId", message.getChatId())
            .put("senderId", message.getSenderId())
            .put("text", message.getText())
            .put("type", message.getType() != null ? message.getType().name().toLowerCase() : "text")
            .put("isRead", message.getIsRead())
            .put("isEdited", message.getIsEdited())
            .put("createdAt", message.getCreatedAt())
            .put("updatedAt", message.getUpdatedAt());

        if (message.getReplyTo() != null) {
            JsonObject replyTo = new JsonObject()
                .put("messageId", message.getReplyTo().getMessageId())
                .put("text", message.getReplyTo().getText())
                .put("senderName", message.getReplyTo().getSenderName());
            response.put("replyTo", replyTo.getMap());
        }

        if (message.getAttachments() != null && !message.getAttachments().isEmpty()) {
            List<Map<String, Object>> attachments = new ArrayList<>();
            message.getAttachments().forEach(attachment -> {
                JsonObject attachmentJson = new JsonObject()
                    .put("type", attachment.getType() != null ? attachment.getType().name().toLowerCase() : null)
                    .put("url", attachment.getUrl());
                if (attachment.getPreview() != null) {
                    attachmentJson.put("preview", attachment.getPreview());
                }
                attachments.add(attachmentJson.getMap());
            });
            response.put("attachments", attachments);
        }

        return response;
    }

    public static JsonObject toChatResponse(Chat chat) {
        if (chat == null) {
            return new JsonObject();
        }

        JsonObject response = new JsonObject()
            .put("id", chat.getId())
            .put("type", chat.getType() != null ? chat.getType().name() : null)
            .put("user1Id", chat.getUser1Id())
            .put("user2Id", chat.getUser2Id())
            .put("unreadCount", chat.getUnreadCount())
            .put("createdAt", chat.getCreatedAt())
            .put("updatedAt", chat.getUpdatedAt())
            .put("isActive", chat.getIsActive());

        if (chat.getLastMessage() != null) {
            response.put("lastMessage", toMessageResponse(chat.getLastMessage()).getMap());
        }

        Chat.ChatSettings settings = chat.getSettings();
        if (settings != null) {
            JsonObject settingsJson = new JsonObject()
                .put("cost", settings.getCost())
                .put("anonymousId", settings.getAnonymousId());
            response.put("settings", settingsJson.getMap());
        } else {
            response.put("settings", new JsonObject());
        }

        return response;
    }
}
