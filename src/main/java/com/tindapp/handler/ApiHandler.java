package com.tindapp.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tindapp.config.AppConfig;
import com.tindapp.model.BlackListItem;
import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.model.Notification;
import com.tindapp.model.Report;
import com.tindapp.model.Subscription;
import com.tindapp.model.User;
import com.tindapp.service.BlackListService;
import com.tindapp.service.ChatService;
import com.tindapp.service.MessageService;
import com.tindapp.service.NotificationService;
import com.tindapp.service.ReportService;
import com.tindapp.service.SubscriptionService;
import com.tindapp.service.UserService;
import com.tindapp.util.DateTimeUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class ApiHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiHandler.class);
    private final ObjectMapper objectMapper;

    public enum ErrorCodes {
        UNAUTHORIZED("UNAUTHORIZED"),
        FORBIDDEN("FORBIDDEN"),
        NOT_FOUND("NOT_FOUND"),
        VALIDATION_ERROR("VALIDATION_ERROR"),
        INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE"),
        RATE_LIMIT_EXCEEDED("RATE_LIMIT_EXCEEDED"),
        CHAT_NOT_FOUND("CHAT_NOT_FOUND"),
        USER_BLOCKED("USER_BLOCKED"),
        SUBSCRIPTION_REQUIRED("SUBSCRIPTION_REQUIRED"),
        SERVER_ERROR("SERVER_ERROR");

        private final String code;

        ErrorCodes(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final UserService userService;
    private final ChatService chatService;
    private final MessageService messageService;
    private final NotificationService notificationService;
    private final SubscriptionService subscriptionService;
    private final ReportService reportService;
    private final BlackListService blackListService;
    private final WebSocketHandler webSocketHandler;

    public ApiHandler(UserService userService, ChatService chatService, MessageService messageService,
                     NotificationService notificationService, SubscriptionService subscriptionService,
                     ReportService reportService, BlackListService blackListService,
                     WebSocketHandler webSocketHandler) {
        this.userService = userService;
        this.chatService = chatService;
        this.messageService = messageService;
        this.notificationService = notificationService;
        this.subscriptionService = subscriptionService;
        this.reportService = reportService;
        this.blackListService = blackListService;
        this.webSocketHandler = webSocketHandler;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void getCurrentUser(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Optional<User> user = userService.getUserById(userId);

            if (user.isPresent()) {
                sendSuccess(ctx, convertUserToResponse(user.get()));
            } else {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            }
        } catch (Exception e) {
            logger.error("Error getting current user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getUser(RoutingContext ctx) {
        try {
            Long userId = Long.valueOf(ctx.pathParam("userId"));
            Optional<User> user = userService.getUserById(userId);

            if (user.isPresent()) {
                sendSuccess(ctx, convertUserToResponse(user.get()));
            } else {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            }
        } catch (Exception e) {
            logger.error("Error getting user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void updateProfile(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            String bio = body.getString("bio");
            String city = body.getString("city");
            Integer age = body.getInteger("age");
            String birthDate = body.getString("birthDate");
            Boolean isVisible = body.getBoolean("isVisible");

            User.UserSettings settings = null;
            JsonObject settingsJson = body.getJsonObject("settings");
            if (settingsJson != null) {
                settings = new User.UserSettings();
                if (settingsJson.containsKey("showAge")) {
                    settings.setShowAge(settingsJson.getBoolean("showAge"));
                }
                if (settingsJson.containsKey("showCity")) {
                    settings.setShowCity(settingsJson.getBoolean("showCity"));
                }
                if (settingsJson.containsKey("allowMessages")) {
                    settings.setAllowMessages(settingsJson.getBoolean("allowMessages"));
                }
            }

            User updatedUser = userService.updateProfile(userId, bio, city, age, birthDate, isVisible, settings);
            sendSuccess(ctx, convertUserToResponse(updatedUser));
        } catch (Exception e) {
            logger.error("Error updating profile", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, e.getMessage());
        }
    }

    public void verifyUser(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            User verifiedUser = userService.verifyUser(userId);

            JsonObject response = new JsonObject()
                .put("isVerified", verifiedUser.getIsVerified());
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error verifying user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getBalance(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Integer balance = userService.getUserBalance(userId);

            JsonObject response = new JsonObject().put("balance", balance);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error getting user balance", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void purchaseCoins(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            Integer amount = body.getInteger("amount");
            String paymentMethod = body.getString("paymentMethod");

            User updatedUser = userService.purchaseCoins(userId, amount, paymentMethod);

            JsonObject response = new JsonObject().put("balance", updatedUser.getBalance());
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error purchasing coins", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getUserStats(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            UserService.UserStats stats = userService.getUserStats(userId);
            sendSuccess(ctx, stats);
        } catch (Exception e) {
            logger.error("Error getting user stats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChatCost(RoutingContext ctx) {
        try {
            int cost = chatService.getChatCost();
            JsonObject response = new JsonObject().put("cost", cost);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error getting chat cost", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChats(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "20"));

            List<Chat> chats = chatService.getUserChats(userId, page, limit);
            sendPaginatedSuccess(ctx, chats, page, limit, chats.size());
        } catch (Exception e) {
            logger.error("Error getting chats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChat(RoutingContext ctx) {
        try {
            String chatId = ctx.pathParam("chatId");
            Long userId = getUserIdFromContext(ctx);

            logger.info("Getting chat: chatId={}, userId={}", chatId, userId);

            if (!chatService.isUserInChat(chatId, userId)) {
                logger.warn("User {} denied access to chat {}", userId, chatId);
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            Optional<Chat> chat = chatService.getChatById(chatId);
            if (chat.isPresent()) {
                logger.info("Chat found and returned: chatId={}", chatId);
                sendSuccess(ctx, chat.get());
            } else {
                logger.warn("Chat not found: chatId={}", chatId);
                sendError(ctx, 404, ErrorCodes.CHAT_NOT_FOUND, "Chat not found");
            }
        } catch (Exception e) {
            logger.error("Error getting chat: chatId={}", ctx.pathParam("chatId"), e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getSearchStatus(RoutingContext ctx) {
        logger.info("=== getSearchStatus method called ===");
        logger.info("Request path: {}, method: {}", ctx.request().path(), ctx.request().method());
        logger.info("Headers: Authorization={}", ctx.request().getHeader("Authorization"));
        try {

            Long userId = getUserIdFromContext(ctx);

            boolean isSearching = chatService.isSearchingCompanion(userId);
            int queueSize = chatService.getSearchQueueSize();

            JsonObject response = new JsonObject()
                .put("isSearching", isSearching)
                .put("queueSize", queueSize);

            sendSuccess(ctx, response);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("User ID not found in context")) {
                logger.error("Authentication error in getSearchStatus: context has currentUser={}, userId={}",
                           ctx.get("currentUser"), ctx.get("userId"), e);
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
            } else {
                logger.error("Runtime error in getSearchStatus", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        } catch (Exception e) {
            logger.error("Error getting search status", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void endChat(RoutingContext ctx) {
        try {
            String chatId = ctx.pathParam("chatId");
            Long userId = getUserIdFromContext(ctx);

            chatService.endChat(chatId, userId);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error ending chat", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getMessages(RoutingContext ctx) {
        try {
            String chatId = ctx.pathParam("chatId");
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "50"));

            if (!chatService.isUserInChat(chatId, userId)) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            List<Message> messages = messageService.getChatMessages(chatId, page, limit);
            sendPaginatedSuccess(ctx, messages, page, limit, messages.size());
        } catch (Exception e) {
            logger.error("Error getting messages", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void sendMessage(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            String chatId = body.getString("chatId");
            String text = body.getString("text");
            String replyToMessageId = body.getString("replyToMessageId");

            Message message = messageService.sendMessage(userId, chatId, text, replyToMessageId);

            logger.info("Broadcasting message via WebSocket: chatId={}, messageId={}", chatId, message.getId());
            webSocketHandler.sendMessageToUser(userId, "message", JsonObject.mapFrom(message));

            Optional<Chat> chatOpt = chatService.getChatById(chatId);
            if (chatOpt.isPresent()) {
                Chat chat = chatOpt.get();
                Long companionId = chat.getCompanionId(userId);
                if (companionId != null) {
                    webSocketHandler.sendMessageToUser(companionId, "message", JsonObject.mapFrom(message));
                }
            }

            sendSuccess(ctx, convertMessageToResponse(message));
        } catch (Exception e) {
            logger.error("Error sending message", e);
            if (e.getMessage().contains("Insufficient balance")) {
                sendError(ctx, 400, ErrorCodes.INSUFFICIENT_BALANCE, "Insufficient balance");
            } else if (e.getMessage().contains("blocked")) {
                sendError(ctx, 403, ErrorCodes.USER_BLOCKED, "User is blocked");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void editMessage(RoutingContext ctx) {
        try {
            String messageId = ctx.pathParam("messageId");
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            String text = body.getString("text");
            Message editedMessage = messageService.editMessage(messageId, userId, text);
            sendSuccess(ctx, convertMessageToResponse(editedMessage));
        } catch (Exception e) {
            logger.error("Error editing message", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Message not found");
            } else if (e.getMessage().contains("permission")) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "No permission to edit message");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void deleteMessage(RoutingContext ctx) {
        try {
            String messageId = ctx.pathParam("messageId");
            Long userId = getUserIdFromContext(ctx);

            messageService.deleteMessage(messageId, userId);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error deleting message", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Message not found");
            } else if (e.getMessage().contains("permission")) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "No permission to delete message");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void createReport(RoutingContext ctx) {
        try {
            Long reporterId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            Long targetId = body.getLong("targetId");
            String chatId = body.getString("chatId");
            String messageId = body.getString("messageId");
            String reasonStr = body.getString("reason");
            String description = body.getString("description");

            Report.ReportReason reason = Report.ReportReason.valueOf(reasonStr.toUpperCase());
            Report report = reportService.createReport(reporterId, targetId, chatId, messageId, reason, description);
            sendSuccess(ctx, report);
        } catch (Exception e) {
            logger.error("Error creating report", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User or resource not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getReports(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "20"));

            List<Report> reports = reportService.getUserReports(userId, page, limit);
            sendPaginatedSuccess(ctx, reports, page, limit, reports.size());
        } catch (Exception e) {
            logger.error("Error getting reports", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void blockUser(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            Long blockedUserId = body.getLong("userId");
            String reason = body.getString("reason");

            BlackListItem blackListItem = blackListService.blockUser(userId, blockedUserId, reason);
            sendSuccess(ctx, blackListItem);
        } catch (Exception e) {
            logger.error("Error blocking user", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            } else if (e.getMessage().contains("already blocked")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "User already blocked");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void unblockUser(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Long blockedUserId = Long.valueOf(ctx.pathParam("userId"));

            blackListService.unblockUser(userId, blockedUserId);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error unblocking user", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found in blacklist");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getBlacklist(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "20"));

            List<BlackListItem> blackList = blackListService.getUserBlackList(userId, page, limit);
            sendPaginatedSuccess(ctx, blackList, page, limit, blackList.size());
        } catch (Exception e) {
            logger.error("Error getting blacklist", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getActiveSubscription(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Optional<Subscription> subscription = subscriptionService.getActiveSubscription(userId);

            if (subscription.isPresent()) {
                sendSuccess(ctx, subscription.get());
            } else {
                sendSuccess(ctx, null);
            }
        } catch (Exception e) {
            logger.error("Error getting active subscription", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void purchaseSubscription(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            String planId = body.getString("planId");
            String paymentMethodStr = body.getString("paymentMethod");
            Subscription.PaymentMethod paymentMethod = Subscription.PaymentMethod.valueOf(paymentMethodStr.toUpperCase());

            Subscription subscription = subscriptionService.purchaseSubscription(userId, planId, paymentMethod);
            sendSuccess(ctx, subscription);
        } catch (Exception e) {
            logger.error("Error purchasing subscription", e);
            if (e.getMessage().contains("Insufficient balance")) {
                sendError(ctx, 400, ErrorCodes.INSUFFICIENT_BALANCE, "Insufficient balance");
            } else if (e.getMessage().contains("already active")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Subscription already active");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void cancelSubscription(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            subscriptionService.cancelSubscription(userId);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error cancelling subscription", e);
            if (e.getMessage().contains("no active subscription")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "No active subscription to cancel");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getNotifications(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "20"));

            List<Notification> notifications = notificationService.getUserNotifications(userId, page, limit);
            sendPaginatedSuccess(ctx, notifications, page, limit, notifications.size());
        } catch (Exception e) {
            logger.error("Error getting notifications", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void markNotificationsAsRead(RoutingContext ctx) {
        try {
            JsonObject body = ctx.getBodyAsJson();
            List<String> notificationIds = body.getJsonArray("notificationIds")
                .stream()
                .map(Object::toString)
                .collect(Collectors.toList());

            notificationService.markNotificationsAsRead(notificationIds);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error marking notifications as read", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void deleteNotification(RoutingContext ctx) {
        try {
            String notificationId = ctx.pathParam("notificationId");
            Long userId = getUserIdFromContext(ctx);

            notificationService.deleteNotification(notificationId, userId);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error deleting notification", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Notification not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getOnlineStats(RoutingContext ctx) {
        try {
            UserService.OnlineStats stats = userService.getOnlineStats();
            JsonObject response = new JsonObject()
                .put("anonymousChats", chatService.getActiveAnonymousChatsCount())
                .put("totalUsers", stats.getTotalUsers())
                .put("activeUsers", stats.getActiveUsers());
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error getting online stats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getAppConfig(RoutingContext ctx) {
        try {
            JsonObject config = AppConfig.getClientConfig();
            sendSuccess(ctx, config);
        } catch (Exception e) {
            logger.error("Error getting app config", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    private Long getUserIdFromContext(RoutingContext ctx) {
        Long userId = ctx.get("userId");
        if (userId == null) {
            throw new RuntimeException("User ID not found in context");
        }
        return userId;
    }

    private void sendSuccess(RoutingContext ctx, Object data) {
        try {
            JsonObject response;
            if (data != null) {
                JsonObject parsedData;

                if (data instanceof JsonObject) {
                    parsedData = (JsonObject) data;
                } else {
                    String dataJson = objectMapper.writeValueAsString(data);
                    parsedData = new JsonObject(dataJson);
                }

                response = new JsonObject()
                    .put("success", true)
                    .put("data", parsedData);
            } else {
                response = new JsonObject()
                    .put("success", true)
                    .put("data", null);
            }

            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            logger.error("Error sending success response", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    private void sendPaginatedSuccess(RoutingContext ctx, List<?> data, int page, int limit, int total) {
        try {
            JsonObject pagination = new JsonObject()
                .put("page", page)
                .put("limit", limit)
                .put("total", total)
                .put("totalPages", (int) Math.ceil((double) total / limit));

            String dataJson = objectMapper.writeValueAsString(data);
            io.vertx.core.json.JsonArray parsedData = new io.vertx.core.json.JsonArray(dataJson);

            JsonObject response = new JsonObject()
                .put("success", true)
                .put("data", parsedData)
                .put("pagination", pagination);

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            logger.error("Error sending paginated response", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Error serializing response");
        }
    }

    private void sendError(RoutingContext ctx, int statusCode, ErrorCodes errorCode, String message) {
        JsonObject error = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("code", errorCode.getCode());

        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(error.encode());
    }

    private JsonObject convertUserToResponse(User user) {
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

        JsonObject subscription = new JsonObject()
            .put("isActive", user.getSubscription() != null ? user.getSubscription().getIsActive() : false)
            .put("type", "basic");
        if (user.getSubscription() != null && user.getSubscription().getExpiresAt() != null) {
            subscription.put("expiresAt", DateTimeUtils.formatToIso(user.getSubscription().getExpiresAt()));
        }
        response.put("subscription", subscription);

        JsonObject settings = new JsonObject();
        if (user.getSettings() != null) {
            settings.put("showAge", user.getSettings().getShowAge())
                   .put("showCity", user.getSettings().getShowCity())
                   .put("allowMessages", user.getSettings().getAllowMessages());
        } else {
            settings.put("showAge", true)
                   .put("showCity", true)
                   .put("allowMessages", true);
        }
        response.put("settings", settings);

        return response;
    }

    private JsonObject convertMessageToResponse(Message message) {
        JsonObject response = new JsonObject()
            .put("id", message.getId())
            .put("chatId", message.getChatId())
            .put("senderId", message.getSenderId())
            .put("text", message.getText())
            .put("type", message.getType() != null ? message.getType().toString().toLowerCase() : "text")
            .put("isRead", message.getIsRead())
            .put("isEdited", message.getIsEdited());

        response.put("createdAt", message.getCreatedAt());
        response.put("updatedAt", message.getUpdatedAt());

        if (message.getReplyTo() != null) {
            JsonObject replyTo = new JsonObject()
                .put("messageId", message.getReplyTo().getMessageId())
                .put("text", message.getReplyTo().getText())
                .put("senderName", message.getReplyTo().getSenderName());
            response.put("replyTo", replyTo);
        }

        if (message.getAttachments() != null && !message.getAttachments().isEmpty()) {
            JsonArray attachments = new JsonArray();
            for (Message.MessageAttachment attachment : message.getAttachments()) {
                JsonObject attachmentObj = new JsonObject()
                    .put("type", attachment.getType().toString().toLowerCase())
                    .put("url", attachment.getUrl());
                if (attachment.getPreview() != null) {
                    attachmentObj.put("preview", attachment.getPreview());
                }
                attachments.add(attachmentObj);
            }
            response.put("attachments", attachments);
        }

        return response;
    }
}
