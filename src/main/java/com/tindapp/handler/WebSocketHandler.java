package com.tindapp.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tindapp.config.AppConfig;
import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.model.User;
import com.tindapp.service.ChatService;
import com.tindapp.service.MessageService;
import com.tindapp.service.NotificationService;
import com.tindapp.service.ProfileService;
import com.tindapp.service.TokenService;
import com.tindapp.service.UserService;
import com.tindapp.util.ResponseMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    private final Vertx vertx;
    private final ObjectMapper objectMapper;
    private final ChatService chatService;
    private final MessageService messageService;
    private final UserService userService;
    private final TokenService tokenService;
    private final ProfileService profileService;
    private final NotificationService notificationService;

    private final Map<Long, ServerWebSocket> userConnections = new ConcurrentHashMap<>();
    private final Map<Integer, Long> socketToUser = new ConcurrentHashMap<>();
    private final Map<Long, String> userChats = new ConcurrentHashMap<>(); // userId -> active chatId
    private final Map<Long, Boolean> typingStatus = new ConcurrentHashMap<>();
    private final Map<Long, ProfileSubscription> profileSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, ChatParticipants> chatParticipantsCache = new ConcurrentHashMap<>();

    public WebSocketHandler(final Vertx vertx, final ChatService chatService, final MessageService messageService, final UserService userService,
                            final TokenService tokenService, final ProfileService profileService, final NotificationService notificationService) {
        this.vertx = vertx;
        this.chatService = chatService;
        this.messageService = messageService;
        this.userService = userService;
        this.tokenService = tokenService;
        this.profileService = profileService;
        this.notificationService = notificationService;

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        startHeartbeatTimer();
        startTypingCleanup();
    }

    public void handle(final ServerWebSocket webSocket) {
        final String path = webSocket.path();

        if (!path.equals("/ws")) {
            webSocket.reject();
            return;
        }

        final String query = webSocket.query();
        if (query == null || !query.startsWith("token=")) {
            logger.warn("WebSocket connection rejected: missing token");
            webSocket.reject();
            return;
        }

        final String token = query.substring(6); // убираем "token="

        runOnWorker(webSocket, () -> {
            final User user = tokenService.validateToken(token);
            if (user == null) {
                logger.warn("WebSocket connection rejected: invalid or expired token: {}", token);
                closeQuietly(webSocket);
                return;
            }

            if (user.getId() == null) {
                logger.error("WebSocket connection rejected: user ID is null for vkId={}", user.getVkId());
                closeQuietly(webSocket);
                return;
            }

            final Integer socketKey = webSocket.hashCode();
            userConnections.put(user.getId(), webSocket);
            socketToUser.put(socketKey, user.getId());

            userService.updateOnlineStatus(user.getId(), true);
            notifyProfileUpdated(user);

            logger.info("WebSocket connection established for user: id={}, vkId={}", user.getId(), user.getVkId());

            vertx.runOnContext(v -> {
                webSocket.handler(buffer -> runOnWorker(webSocket, () -> {
                    final String message = buffer.toString();
                    final JsonObject messageObj = new JsonObject(message);
                    handleWebSocketMessage(webSocket, messageObj);
                }, "Error handling WebSocket message"));

                webSocket.closeHandler(v2 -> runOnWorker(webSocket, () -> handleWebSocketClose(webSocket),
                    "Error on WebSocket close"));

                webSocket.exceptionHandler(throwable -> {
                    logger.error("WebSocket exception", throwable);
                    runOnWorker(webSocket, () -> handleWebSocketClose(webSocket), "WebSocket exception");
                });

                sendMessage(webSocket, "connected", new JsonObject().put("status", "connected"));
            });
        }, "Error establishing WebSocket connection");
    }

    private void handleWebSocketMessage(final ServerWebSocket webSocket, final JsonObject message) {
        final String type = message.getString("type");
        final JsonObject data = message.getJsonObject("data", new JsonObject());

        switch (type) {
            case "auth":
                handleAuth(webSocket, data);
                break;
            case "join_chat":
                handleJoinChat(webSocket, data);
                break;
            case "leave_chat":
                handleLeaveChat(webSocket, data);
                break;
            case "send_message":
                handleSendMessage(webSocket, data);
                break;
            case "typing":
                handleTyping(webSocket, data);
                break;
            case "read":
                handleReadReceipt(webSocket, data);
                break;
            case "ping":
                handlePing(webSocket);
                break;
            case "pong":
                break;
            case "start_search":
                handleStartCompanionSearch(webSocket, data);
                break;
            case "stop_search":
                handleStopCompanionSearch(webSocket);
                break;
            case "online_status":
                updateOnlineStatus(data);
                break;
            case "profiles_subscribe":
                handleProfilesSubscribe(webSocket, data);
                break;
            case "profiles_unsubscribe":
                handleProfilesUnsubscribe(webSocket);
                break;
            default:
                logger.warn("Unknown WebSocket message type: {}", type);
                sendError(webSocket, "Unknown message type");
        }
    }

    private void handleAuth(final ServerWebSocket webSocket, final JsonObject data) {
        final Long userId = socketToUser.get(webSocket.hashCode());
        if (userId != null) {
            final JsonObject authData = new JsonObject()
                .put("userId", userId)
                .put("authenticated", true);
            sendMessage(webSocket, "authenticated", authData);
        } else {
            sendError(webSocket, "Not authenticated");
        }
    }

    private void handleJoinChat(final ServerWebSocket webSocket, final JsonObject data) {
        try {
            final Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            final String chatId = data.getString("chatId");
            if (chatId == null) {
                sendError(webSocket, "Missing chatId");
                return;
            }

            if (!chatService.isUserInChat(chatId, userId)) {
                sendError(webSocket, "Access denied to chat");
                return;
            }

            final Optional<com.tindapp.model.Chat> chatOpt = chatService.getChatById(chatId);
            if (chatOpt.isEmpty()) {
                sendError(webSocket, "Chat not found");
                return;
            }

            final com.tindapp.model.Chat chat = chatOpt.get();
            cacheChatParticipants(chat);
            final Long companionId = chat.getCompanionId(userId);
            final boolean companionInChat = isUserActiveInChat(companionId, chatId);

            final String previousChatId = userChats.get(userId);
            if (previousChatId != null && !previousChatId.equals(chatId)) {
                handleLeaveChat(webSocket, new JsonObject().put("chatId", previousChatId));
            }

            userChats.put(userId, chatId);
            logger.info("User {} joined chat {}", userId, chatId);

            final JsonArray activeParticipants = new JsonArray().add(userId);
            if (companionInChat && companionId != null) {
                activeParticipants.add(companionId);
            }

            final JsonObject joinData = new JsonObject()
                .put("chatId", chatId)
                .put("joined", true)
                .put("userId", userId)
                .put("companionId", companionId)
                .put("companionInChat", companionInChat)
                .put("activeParticipantIds", activeParticipants);
            sendMessage(webSocket, "chat_joined", joinData);

            notifyOtherParticipants(chatId, userId, "user_joined", new JsonObject()
                .put("userId", userId)
                .put("chatId", chatId));

        } catch (final Exception e) {
            logger.error("Error joining chat", e);
            sendError(webSocket, "Failed to join chat");
        }
    }

    private void handleLeaveChat(final ServerWebSocket webSocket, final JsonObject data) {
        try {
            final Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            String chatId = data.getString("chatId");
            if (chatId == null) {
                chatId = userChats.get(userId);
            }

            if (chatId != null) {
                userChats.remove(userId);
                logger.info("User {} left chat {}", userId, chatId);

                final JsonObject leaveData = new JsonObject()
                    .put("chatId", chatId)
                    .put("left", true);
                sendMessage(webSocket, "chat_left", leaveData);

                notifyOtherParticipants(chatId, userId, "user_left", new JsonObject()
                    .put("userId", userId)
                    .put("chatId", chatId));
            }

        } catch (final Exception e) {
            logger.error("Error leaving chat", e);
            sendError(webSocket, "Failed to leave chat");
        }
    }

    private void handleSendMessage(final ServerWebSocket webSocket, final JsonObject data) {
        try {
            final Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            final String chatId = data.getString("chatId");
            final String text = data.getString("text", "");
            final String replyToMessageId = data.getString("replyToMessageId");
            final io.vertx.core.json.JsonArray attachmentsJson = data.getJsonArray("attachments");
            final List<Message.MessageAttachment> attachments = parseAttachments(attachmentsJson);

            if (chatId == null) {
                sendError(webSocket, "Missing chatId");
                return;
            }

            if (text.trim().isEmpty() && attachments.isEmpty()) {
                sendError(webSocket, "Missing message content");
                return;
            }

            final Message message = messageService.sendMessage(userId, chatId, text, replyToMessageId, attachments);
            final JsonObject messageJson = ResponseMapper.toMessageResponse(message);

            broadcastToChat(chatId, "message", messageJson);

        } catch (final Exception e) {
            logger.error("Error sending message via WebSocket", e);
            sendError(webSocket, e.getMessage());
        }
    }

    private void handleTyping(final ServerWebSocket webSocket, final JsonObject data) {
        try {
            final Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            final String chatId = data.getString("chatId");
            final Boolean isTyping = data.getBoolean("isTyping", false);

            if (chatId == null) {
                sendError(webSocket, "Missing chatId");
                return;
            }

            typingStatus.put(userId, isTyping);

            final JsonObject typingData = new JsonObject()
                .put("userId", userId)
                .put("chatId", chatId)
                .put("isTyping", isTyping);

            notifyOtherParticipants(chatId, userId, "typing", typingData);

            if (isTyping) {
                vertx.setTimer(5000, timerId -> {
                    typingStatus.remove(userId);
                    final JsonObject stopTypingData = new JsonObject()
                        .put("userId", userId)
                        .put("chatId", chatId)
                        .put("isTyping", false);
                    notifyOtherParticipants(chatId, userId, "typing", stopTypingData);
                });
            }

        } catch (final Exception e) {
            logger.error("Error handling typing indicator", e);
            sendError(webSocket, "Failed to update typing status");
        }
    }

    private void handleReadReceipt(final ServerWebSocket webSocket, final JsonObject data) {
        try {
            final Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            final String chatId = data.getString("chatId");
            final String messageId = data.getString("messageId");

            if (chatId == null || messageId == null) {
                sendError(webSocket, "Missing chatId or messageId");
                return;
            }

            try {
                messageService.markMessagesAsRead(chatId, userId, List.of(messageId));
            } catch (final Exception e) {
                logger.warn("Error marking message as read in database: {}", e.getMessage());
            }

            final JsonObject readData = new JsonObject()
                .put("chatId", chatId)
                .put("messageId", messageId)
                .put("userId", userId)
                .put("readAt", data.getString("readAt", LocalDateTime.now().toString()));

            notifyOtherParticipants(chatId, userId, "read", readData);

        } catch (final Exception e) {
            logger.error("Error handling read receipt", e);
            sendError(webSocket, "Failed to process read receipt");
        }
    }

    private void handlePing(final ServerWebSocket webSocket) {
        sendMessage(webSocket, "pong", new JsonObject().put("timestamp", System.currentTimeMillis()));
    }

    private void handleStartCompanionSearch(final ServerWebSocket webSocket, final JsonObject data) {
        try {
            final Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            final boolean isAlreadySearching = chatService.isSearchingCompanion(userId);
            if (isAlreadySearching) {
                logger.info("User {} is already searching, restarting search with new filters", userId);
                chatService.cancelCompanionSearch(userId);
            }

            final JsonObject filters = data.getJsonObject("filters", new JsonObject());

            final ChatService.SearchFilters searchFilters = new ChatService.SearchFilters(
                filters.getString("gender", "any"),
                filters.getJsonArray("ageRange", new io.vertx.core.json.JsonArray().add(18).add(80))
                    .stream().mapToInt(o -> (Integer) o).toArray(),
                filters.getString("preference", "communication"),
                filters.getString("city")
            );

            final ChatService.FindCompanionResult findResult = chatService.findCompanion(userId, searchFilters);

            if (findResult.inQueue()) {
                final JsonObject queueResponse = new JsonObject()
                    .put("inQueue", true)
                    .put("queueSize", findResult.queueSize())
                    .put("message", findResult.message());
                sendMessage(webSocket, "search_queued", queueResponse);
            } else if (findResult.matchResult() != null) {
                final ChatService.MatchResult result = findResult.matchResult();

                sendMessage(webSocket, "match_found", JsonObject.mapFrom(result));

                final ServerWebSocket companionSocket = userConnections.get(result.companion().id());
                if (companionSocket != null) {
                    final JsonObject companionMatchData = new JsonObject()
                        .put("chatId", result.chatId())
                        .put("cost", result.cost())
                        .put("companion", new JsonObject()
                            .put("id", userId)
                            .put("nickname", "Собеседник #" + userId)
                            .put("isVerified", false)
                            .put("isOnline", true)
                        );
                    sendMessage(companionSocket, "match_found", companionMatchData);
                }

                sendMatchNotifications(userId, result);
            }

        } catch (final Exception e) {
            logger.error("Error starting companion search", e);
            sendError(webSocket, e.getMessage());
        }
    }

    private void handleStopCompanionSearch(final ServerWebSocket webSocket) {
        final Long userId = getUserId(webSocket);
        if (userId == null) {
            sendError(webSocket, "Not authenticated");
            return;
        }

        try {
            final boolean wasStopped = chatService.cancelCompanionSearch(userId);
            final JsonObject response = new JsonObject()
                .put("stopped", wasStopped)
                .put("message", wasStopped ? "Search stopped" : "No active search");

            sendMessage(webSocket, "search_stopped", response);
            logger.info("User {} stopped companion search: {}", userId, wasStopped);
        } catch (final Exception e) {
            logger.error("Error stopping companion search for user: " + userId, e);
            sendError(webSocket, "Failed to stop search: " + e.getMessage());
        }
    }

    private void handleWebSocketClose(final ServerWebSocket webSocket) {
        Long userId = getUserId(webSocket);

        if (userId == null) {
            for (final Map.Entry<Long, ServerWebSocket> entry : userConnections.entrySet()) {
                if (entry.getValue().equals(webSocket)) {
                    userId = entry.getKey();
                    userConnections.remove(userId);
                    break;
                }
            }
        } else {
            userConnections.remove(userId);
        }

        final Integer socketKey = webSocket.hashCode();
        socketToUser.remove(socketKey);

        if (userId != null) {
            final String activeChatId = userChats.remove(userId);
            if (activeChatId != null) {
                notifyOtherParticipants(activeChatId, userId, "user_left", new JsonObject()
                    .put("userId", userId)
                    .put("chatId", activeChatId));
            }
            typingStatus.remove(userId);
            profileSubscriptions.remove(userId);

            userService.updateOnlineStatus(userId, false);
            notifyProfileUpdated(userId);

            logger.info("User {} disconnected from WebSocket", userId);
        } else {
            logger.warn("Could not identify user for disconnected WebSocket");
        }
    }

    private void updateOnlineStatus(final JsonObject data) {
    }

    private Long getUserId(final ServerWebSocket webSocket) {
        return socketToUser.get(webSocket.hashCode());
    }

    private void handleProfilesSubscribe(final ServerWebSocket webSocket, final JsonObject data) {
        try {
            final Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            final User viewer = userService.getUserById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

            final JsonObject filtersJson = data.getJsonObject("filters", new JsonObject());
            final ProfileService.ProfileFilters filters = profileService.parseFilters(filtersJson, viewer);
            profileSubscriptions.put(userId, new ProfileSubscription(userId, filters, webSocket));

            final JsonObject payload = new JsonObject()
                .put("filters", JsonObject.mapFrom(filters));
            sendMessage(webSocket, "profiles_subscribed", payload);
        } catch (final Exception e) {
            logger.error("Error subscribing to profiles", e);
            sendError(webSocket, "Failed to subscribe to profiles");
        }
    }

    private void handleProfilesUnsubscribe(final ServerWebSocket webSocket) {
        final Long userId = getUserId(webSocket);
        if (userId == null) {
            sendError(webSocket, "Not authenticated");
            return;
        }
        profileSubscriptions.remove(userId);
        sendMessage(webSocket, "profiles_unsubscribed", new JsonObject().put("success", true));
    }

    public void notifyProfileUpdated(final Long userId) {
        if (userId == null) {
            return;
        }
        userService.getUserById(userId).ifPresent(this::notifyProfileUpdated);
    }

    public void notifyProfileUpdated(final User updatedUser) {
        if (updatedUser == null) {
            return;
        }

        profileSubscriptions.forEach((subscriberId, subscription) -> {
            try {
                final User viewer = userService.getUserById(subscriberId).orElse(null);
                if (viewer == null) {
                    return;
                }

                final boolean matches = profileService.matchesFilters(viewer, updatedUser, subscription.filters);
                final JsonObject payload = new JsonObject()
                    .put("profileId", updatedUser.getId())
                    .put("isMatch", matches);

                if (matches) {
                    final ProfileService.ProfileCard card = profileService.toProfileCard(viewer, updatedUser);
                    payload.put("profile", JsonObject.mapFrom(card));
                }

                sendMessage(subscription.webSocket, "profiles_changed", payload);
            } catch (final Exception e) {
                logger.error("Error notifying profile update", e);
            }
        });
    }

    private void sendMatchNotifications(final Long currentUserId, final ChatService.MatchResult result) {
        if (notificationService == null || result == null) {
            return;
        }

        try {
            final Long companionUserId = result.companion() != null ? result.companion().id() : null;
            notificationService.sendMatchFoundNotification(currentUserId, "Собеседник");

            if (companionUserId != null) {
                notificationService.sendMatchFoundNotification(companionUserId, "Собеседник");
            }
        } catch (final Exception e) {
            logger.warn("Failed to send match notifications", e);
        }
    }

    public void notifyChatClosed(final String chatId, final Long closedByUserId, final Chat.ChatClosureReason reason, final String closedAt) {
        if (chatId == null) {
            return;
        }

        final JsonObject payload = new JsonObject()
            .put("chatId", chatId)
            .put("closedByUserId", closedByUserId)
            .put("reason", reason != null ? reason.name() : null)
            .put("closedAt", closedAt);

        broadcastToChat(chatId, "chat_closed", payload);
    }

    public void notifyChatReopened(final String chatId) {
        if (chatId == null) {
            return;
        }

        final JsonObject payload = new JsonObject()
            .put("chatId", chatId);

        broadcastToChat(chatId, "chat_reopened", payload);
    }

    private record ProfileSubscription(Long userId, ProfileService.ProfileFilters filters, ServerWebSocket webSocket) {
    }

    private record ChatParticipants(Long user1Id, Long user2Id) {
        private Long companionId(final Long userId) {
            if (userId == null) {
                return null;
            }
            if (userId.equals(user1Id)) {
                return user2Id;
            }
            if (userId.equals(user2Id)) {
                return user1Id;
            }
            return null;
        }
    }

    private List<Message.MessageAttachment> parseAttachments(final io.vertx.core.json.JsonArray attachmentsJson) {
        final List<Message.MessageAttachment> attachments = new ArrayList<>();
        if (attachmentsJson == null || attachmentsJson.isEmpty()) {
            return attachments;
        }

        for (int i = 0; i < attachmentsJson.size(); i++) {
            final Object raw = attachmentsJson.getValue(i);
            if (!(raw instanceof JsonObject attachmentJson)) {
                continue;
            }
            final String typeString = attachmentJson.getString("type", "image");
            Message.MessageAttachment.AttachmentType type;
            try {
                type = Message.MessageAttachment.AttachmentType.valueOf(typeString.toUpperCase());
            } catch (final IllegalArgumentException e) {
                type = Message.MessageAttachment.AttachmentType.IMAGE;
            }
            final String url = attachmentJson.getString("url");
            if (url == null || url.isEmpty()) {
                continue;
            }
            final String preview = attachmentJson.getString("preview", url);
            attachments.add(new Message.MessageAttachment(type, url, preview));
        }
        return attachments;
    }

    private void broadcastToChat(final String chatId, final String type, final JsonObject data) {
        try {
            final ChatParticipants participants = resolveChatParticipants(chatId);
            if (participants == null) {
                return;
            }

            final ServerWebSocket socket1 = userConnections.get(participants.user1Id());
            if (socket1 != null) {
                sendMessage(socket1, type, data);
            }

            final ServerWebSocket socket2 = userConnections.get(participants.user2Id());
            if (socket2 != null) {
                sendMessage(socket2, type, data);
            }
        } catch (final Exception e) {
            logger.error("Error broadcasting to chat", e);
        }
    }

    private void notifyOtherParticipants(final String chatId, final Long excludeUserId, final String type, final JsonObject data) {
        try {
            final ChatParticipants participants = resolveChatParticipants(chatId);
            if (participants == null) {
                return;
            }

            final Long companionId = participants.companionId(excludeUserId);
            if (companionId != null) {
                final ServerWebSocket socket = userConnections.get(companionId);
                if (socket != null) {
                    sendMessage(socket, type, data);
                }
            }
        } catch (final Exception e) {
            logger.error("Error notifying other participants", e);
        }
    }

    private boolean isUserActiveInChat(final Long userId, final String chatId) {
        if (userId == null || chatId == null) {
            return false;
        }
        final String activeChatId = userChats.get(userId);
        return chatId.equals(activeChatId);
    }

    private void sendMessage(final ServerWebSocket webSocket, final String type, final JsonObject data) {
        try {
            final JsonObject message = new JsonObject()
                .put("type", type)
                .put("data", data)
                .put("timestamp", LocalDateTime.now().toString());

            webSocket.writeTextMessage(message.encode());
        } catch (final Exception e) {
            logger.error("Error sending WebSocket message", e);
        }
    }

    private void sendError(final ServerWebSocket webSocket, final String error) {
        final JsonObject errorData = new JsonObject()
            .put("error", error);
        sendMessage(webSocket, "error", errorData);
    }

    private void startHeartbeatTimer() {
        vertx.setPeriodic(30000, timerId -> {
            userConnections.values().forEach(socket -> {
                try {
                    sendMessage(socket, "ping", new JsonObject().put("timestamp", System.currentTimeMillis()));
                } catch (final Exception e) {
                }
            });
        });
    }

    private void startTypingCleanup() {
        vertx.setPeriodic(AppConfig.TYPING_CLEANUP_INTERVAL, timerId -> {
            typingStatus.clear();
        });
    }

    public void sendNotificationToUser(final Long userId, final JsonObject notification) {
        final ServerWebSocket socket = userConnections.get(userId);
        if (socket != null) {
            sendMessage(socket, "notification", notification);
        }
    }

    public void sendMessageToUser(final Long userId, final String type, final JsonObject data) {
        final ServerWebSocket socket = userConnections.get(userId);
        if (socket != null) {
            logger.info("Sending WebSocket message to user {}: type={}, data={}", userId, type, data.encode());
            sendMessage(socket, type, data);
        } else {
            logger.warn("Cannot send WebSocket message to user {}: user not connected", userId);
        }
    }

    public boolean isUserOnline(final Long userId) {
        return userConnections.containsKey(userId);
    }

    private void cacheChatParticipants(final Chat chat) {
        if (chat == null || chat.getId() == null || chat.getUser1Id() == null || chat.getUser2Id() == null) {
            return;
        }
        chatParticipantsCache.put(chat.getId(), new ChatParticipants(chat.getUser1Id(), chat.getUser2Id()));
    }

    private ChatParticipants resolveChatParticipants(final String chatId) {
        if (chatId == null) {
            return null;
        }

        final ChatParticipants cached = chatParticipantsCache.get(chatId);
        if (cached != null) {
            return cached;
        }

        if (io.vertx.core.Context.isOnEventLoopThread()) {
            logger.warn("Skipped uncached chat lookup on event loop for chat {}", chatId);
            return null;
        }

        final Optional<Chat> chatOpt = chatService.getChatById(chatId);
        if (chatOpt.isEmpty()) {
            return null;
        }

        cacheChatParticipants(chatOpt.get());
        return chatParticipantsCache.get(chatId);
    }

    private void runOnWorker(final ServerWebSocket webSocket, final Runnable action, final String errorContext) {
        vertx.<Void>executeBlocking(promise -> {
            try {
                action.run();
                promise.complete();
            } catch (final Exception e) {
                promise.fail(e);
            }
        }, false, ar -> {
            if (ar.failed()) {
                logger.error(errorContext, ar.cause());
                sendError(webSocket, "Internal server error");
            }
        });
    }

    private void closeQuietly(final ServerWebSocket webSocket) {
        try {
            webSocket.close();
        } catch (final Exception ignored) {
        }
    }
}
