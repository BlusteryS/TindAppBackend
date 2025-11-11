package com.tindapp.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tindapp.config.AppConfig;
import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.model.User;
import com.tindapp.service.ChatService;
import com.tindapp.service.MessageService;
import com.tindapp.service.ProfileService;
import com.tindapp.service.TokenService;
import com.tindapp.service.UserService;
import com.tindapp.util.ResponseMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
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

    private final Map<Long, ServerWebSocket> userConnections = new ConcurrentHashMap<>();
    private final Map<Integer, Long> socketToUser = new ConcurrentHashMap<>();
    private final Map<Long, String> userChats = new ConcurrentHashMap<>(); // userId -> active chatId
    private final Map<Long, Boolean> typingStatus = new ConcurrentHashMap<>();
    private final Map<Long, ProfileSubscription> profileSubscriptions = new ConcurrentHashMap<>();

    public WebSocketHandler(Vertx vertx, ChatService chatService, MessageService messageService, UserService userService,
                            TokenService tokenService, ProfileService profileService) {
        this.vertx = vertx;
        this.chatService = chatService;
        this.messageService = messageService;
        this.userService = userService;
        this.tokenService = tokenService;
        this.profileService = profileService;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        startHeartbeatTimer();
        startTypingCleanup();
    }

    public void handle(ServerWebSocket webSocket) {
        String path = webSocket.path();

        if (!path.equals("/ws")) {
            webSocket.reject();
            return;
        }

        String query = webSocket.query();
        if (query == null || !query.startsWith("token=")) {
            logger.warn("WebSocket connection rejected: missing token");
            webSocket.reject();
            return;
        }

        String token = query.substring(6); // убираем "token="

        User user = tokenService.validateToken(token);
        if (user == null) {
            logger.warn("WebSocket connection rejected: invalid or expired token: {}", token);
            webSocket.reject();
            return;
        }

        if (user.getId() == null) {
            logger.error("WebSocket connection rejected: user ID is null for vkId={}", user.getVkId());
            webSocket.reject();
            return;
        }

        Integer socketKey = webSocket.hashCode();

        userConnections.put(user.getId(), webSocket);
        socketToUser.put(socketKey, user.getId());

        userService.updateOnlineStatus(user.getId(), true);
        notifyProfileUpdated(user);

        logger.info("WebSocket connection established for user: id={}, vkId={}", user.getId(), user.getVkId());

        webSocket.handler(buffer -> {
            try {
                String message = buffer.toString();
                JsonObject messageObj = new JsonObject(message);
                handleWebSocketMessage(webSocket, messageObj);
            } catch (Exception e) {
                logger.error("Error handling WebSocket message", e);
                sendError(webSocket, "Invalid message format");
            }
        });

        webSocket.closeHandler(v -> {
            handleWebSocketClose(webSocket);
        });

        webSocket.exceptionHandler(throwable -> {
            logger.error("WebSocket exception", throwable);
            handleWebSocketClose(webSocket);
        });

        sendMessage(webSocket, "connected", new JsonObject().put("status", "connected"));
    }

    private void handleWebSocketMessage(ServerWebSocket webSocket, JsonObject message) {
        String type = message.getString("type");
        JsonObject data = message.getJsonObject("data", new JsonObject());

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

    private void handleAuth(ServerWebSocket webSocket, JsonObject data) {
        Long userId = socketToUser.get(webSocket.textHandlerID());
        if (userId != null) {
            JsonObject authData = new JsonObject()
                .put("userId", userId)
                .put("authenticated", true);
            sendMessage(webSocket, "authenticated", authData);
        } else {
            sendError(webSocket, "Not authenticated");
        }
    }

    private void handleJoinChat(ServerWebSocket webSocket, JsonObject data) {
        try {
            Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            String chatId = data.getString("chatId");
            if (chatId == null) {
                sendError(webSocket, "Missing chatId");
                return;
            }

            if (!chatService.isUserInChat(chatId, userId)) {
                sendError(webSocket, "Access denied to chat");
                return;
            }

            String previousChatId = userChats.get(userId);
            if (previousChatId != null && !previousChatId.equals(chatId)) {
                handleLeaveChat(webSocket, new JsonObject().put("chatId", previousChatId));
            }

            userChats.put(userId, chatId);
            logger.info("User {} joined chat {}", userId, chatId);

            JsonObject joinData = new JsonObject()
                .put("chatId", chatId)
                .put("joined", true);
            sendMessage(webSocket, "chat_joined", joinData);

            notifyOtherParticipants(chatId, userId, "user_joined", new JsonObject()
                .put("userId", userId)
                .put("chatId", chatId));

        } catch (Exception e) {
            logger.error("Error joining chat", e);
            sendError(webSocket, "Failed to join chat");
        }
    }

    private void handleLeaveChat(ServerWebSocket webSocket, JsonObject data) {
        try {
            Long userId = getUserId(webSocket);
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

                JsonObject leaveData = new JsonObject()
                    .put("chatId", chatId)
                    .put("left", true);
                sendMessage(webSocket, "chat_left", leaveData);

                notifyOtherParticipants(chatId, userId, "user_left", new JsonObject()
                    .put("userId", userId)
                    .put("chatId", chatId));
            }

        } catch (Exception e) {
            logger.error("Error leaving chat", e);
            sendError(webSocket, "Failed to leave chat");
        }
    }

    private void handleSendMessage(ServerWebSocket webSocket, JsonObject data) {
        try {
            Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            String chatId = data.getString("chatId");
            String text = data.getString("text", "");
            String replyToMessageId = data.getString("replyToMessageId");
            io.vertx.core.json.JsonArray attachmentsJson = data.getJsonArray("attachments");
            List<Message.MessageAttachment> attachments = parseAttachments(attachmentsJson);

            if (chatId == null) {
                sendError(webSocket, "Missing chatId");
                return;
            }

            if (text.trim().isEmpty() && attachments.isEmpty()) {
                sendError(webSocket, "Missing message content");
                return;
            }

            Message message = messageService.sendMessage(userId, chatId, text, replyToMessageId, attachments);
            JsonObject messageJson = ResponseMapper.toMessageResponse(message);

            broadcastToChat(chatId, "message", messageJson);

        } catch (Exception e) {
            logger.error("Error sending message via WebSocket", e);
            sendError(webSocket, e.getMessage());
        }
    }

    private void handleTyping(ServerWebSocket webSocket, JsonObject data) {
        try {
            Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            String chatId = data.getString("chatId");
            Boolean isTyping = data.getBoolean("isTyping", false);

            if (chatId == null) {
                sendError(webSocket, "Missing chatId");
                return;
            }

            typingStatus.put(userId, isTyping);

            JsonObject typingData = new JsonObject()
                .put("userId", userId)
                .put("chatId", chatId)
                .put("isTyping", isTyping);

            notifyOtherParticipants(chatId, userId, "typing", typingData);

            if (isTyping) {
                vertx.setTimer(5000, timerId -> {
                    typingStatus.remove(userId);
                    JsonObject stopTypingData = new JsonObject()
                        .put("userId", userId)
                        .put("chatId", chatId)
                        .put("isTyping", false);
                    notifyOtherParticipants(chatId, userId, "typing", stopTypingData);
                });
            }

        } catch (Exception e) {
            logger.error("Error handling typing indicator", e);
            sendError(webSocket, "Failed to update typing status");
        }
    }

    private void handleReadReceipt(ServerWebSocket webSocket, JsonObject data) {
        try {
            Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            String chatId = data.getString("chatId");
            String messageId = data.getString("messageId");

            if (chatId == null || messageId == null) {
                sendError(webSocket, "Missing chatId or messageId");
                return;
            }

            try {
                messageService.markMessagesAsRead(chatId, userId, List.of(messageId));
            } catch (Exception e) {
                logger.warn("Error marking message as read in database: {}", e.getMessage());
            }

            JsonObject readData = new JsonObject()
                .put("chatId", chatId)
                .put("messageId", messageId)
                .put("userId", userId)
                .put("readAt", data.getString("readAt", LocalDateTime.now().toString()));

            notifyOtherParticipants(chatId, userId, "read", readData);

        } catch (Exception e) {
            logger.error("Error handling read receipt", e);
            sendError(webSocket, "Failed to process read receipt");
        }
    }

    private void handlePing(ServerWebSocket webSocket) {
        sendMessage(webSocket, "pong", new JsonObject().put("timestamp", System.currentTimeMillis()));
    }

    private void handleStartCompanionSearch(ServerWebSocket webSocket, JsonObject data) {
        try {
            Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            boolean isAlreadySearching = chatService.isSearchingCompanion(userId);
            if (isAlreadySearching) {
                logger.info("User {} is already searching, restarting search with new filters", userId);
                chatService.cancelCompanionSearch(userId);
            }

            JsonObject filters = data.getJsonObject("filters", new JsonObject());

            ChatService.SearchFilters searchFilters = new ChatService.SearchFilters(
                filters.getString("gender", "any"),
                filters.getJsonArray("ageRange", new io.vertx.core.json.JsonArray().add(18).add(80))
                    .stream().mapToInt(o -> (Integer) o).toArray(),
                filters.getString("preference", "communication"),
                filters.getString("city")
            );

            ChatService.FindCompanionResult findResult = chatService.findCompanion(userId, searchFilters);

            if (findResult.isInQueue()) {
                JsonObject queueResponse = new JsonObject()
                    .put("inQueue", true)
                    .put("queueSize", findResult.getQueueSize())
                    .put("message", findResult.getMessage());
                sendMessage(webSocket, "search_queued", queueResponse);
            } else if (findResult.getMatchResult() != null) {
                ChatService.MatchResult result = findResult.getMatchResult();

                sendMessage(webSocket, "match_found", JsonObject.mapFrom(result));

                ServerWebSocket companionSocket = userConnections.get(result.getCompanion().getId());
                if (companionSocket != null) {
                    JsonObject companionMatchData = new JsonObject()
                        .put("chatId", result.getChatId())
                        .put("cost", result.getCost())
                        .put("companion", new JsonObject()
                            .put("id", userId)
                            .put("nickname", "Собеседник #" + userId)
                            .put("isVerified", false)
                            .put("isOnline", true)
                        );
                    sendMessage(companionSocket, "match_found", companionMatchData);
                }
            }

        } catch (Exception e) {
            logger.error("Error starting companion search", e);
            sendError(webSocket, e.getMessage());
        }
    }

    private void handleStopCompanionSearch(ServerWebSocket webSocket) {
        Long userId = getUserId(webSocket);
        if (userId == null) {
            sendError(webSocket, "Not authenticated");
            return;
        }

        try {
            boolean wasStopped = chatService.cancelCompanionSearch(userId);
            JsonObject response = new JsonObject()
                .put("stopped", wasStopped)
                .put("message", wasStopped ? "Search stopped" : "No active search");

            sendMessage(webSocket, "search_stopped", response);
            logger.info("User {} stopped companion search: {}", userId, wasStopped);
        } catch (Exception e) {
            logger.error("Error stopping companion search for user: " + userId, e);
            sendError(webSocket, "Failed to stop search: " + e.getMessage());
        }
    }

    private void handleWebSocketClose(ServerWebSocket webSocket) {
        Long userId = getUserId(webSocket);

        if (userId == null) {
            for (Map.Entry<Long, ServerWebSocket> entry : userConnections.entrySet()) {
                if (entry.getValue().equals(webSocket)) {
                    userId = entry.getKey();
                    userConnections.remove(userId);
                    break;
                }
            }
        } else {
            userConnections.remove(userId);
        }

        Integer socketKey = webSocket.hashCode();
        socketToUser.remove(socketKey);

        if (userId != null) {
            userChats.remove(userId);
            typingStatus.remove(userId);
            profileSubscriptions.remove(userId);

            userService.updateOnlineStatus(userId, false);
            notifyProfileUpdated(userId);

            logger.info("User {} disconnected from WebSocket", userId);
        } else {
            logger.warn("Could not identify user for disconnected WebSocket");
        }
    }

    private void updateOnlineStatus(JsonObject data) {
    }

    private Long getUserId(ServerWebSocket webSocket) {
        return socketToUser.get(webSocket.hashCode());
    }

    private void handleProfilesSubscribe(ServerWebSocket webSocket, JsonObject data) {
        try {
            Long userId = getUserId(webSocket);
            if (userId == null) {
                sendError(webSocket, "Not authenticated");
                return;
            }

            User viewer = userService.getUserById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

            JsonObject filtersJson = data.getJsonObject("filters", new JsonObject());
            ProfileService.ProfileFilters filters = profileService.parseFilters(filtersJson, viewer);
            profileSubscriptions.put(userId, new ProfileSubscription(userId, filters, webSocket));

            JsonObject payload = new JsonObject()
                .put("filters", JsonObject.mapFrom(filters));
            sendMessage(webSocket, "profiles_subscribed", payload);
        } catch (Exception e) {
            logger.error("Error subscribing to profiles", e);
            sendError(webSocket, "Failed to subscribe to profiles");
        }
    }

    private void handleProfilesUnsubscribe(ServerWebSocket webSocket) {
        Long userId = getUserId(webSocket);
        if (userId == null) {
            sendError(webSocket, "Not authenticated");
            return;
        }
        profileSubscriptions.remove(userId);
        sendMessage(webSocket, "profiles_unsubscribed", new JsonObject().put("success", true));
    }

    public void notifyProfileUpdated(Long userId) {
        if (userId == null) {
            return;
        }
        userService.getUserById(userId).ifPresent(this::notifyProfileUpdated);
    }

    public void notifyProfileUpdated(User updatedUser) {
        if (updatedUser == null) {
            return;
        }

        profileSubscriptions.forEach((subscriberId, subscription) -> {
            try {
                User viewer = userService.getUserById(subscriberId).orElse(null);
                if (viewer == null) {
                    return;
                }

                boolean matches = profileService.matchesFilters(viewer, updatedUser, subscription.filters);
                JsonObject payload = new JsonObject()
                    .put("profileId", updatedUser.getId())
                    .put("isMatch", matches);

                if (matches) {
                    ProfileService.ProfileCard card = profileService.toProfileCard(viewer, updatedUser);
                    payload.put("profile", JsonObject.mapFrom(card));
                }

                sendMessage(subscription.webSocket, "profiles_changed", payload);
            } catch (Exception e) {
                logger.error("Error notifying profile update", e);
            }
        });
    }

    private static class ProfileSubscription {
        private final Long userId;
        private final ProfileService.ProfileFilters filters;
        private final ServerWebSocket webSocket;

        private ProfileSubscription(Long userId, ProfileService.ProfileFilters filters, ServerWebSocket webSocket) {
            this.userId = userId;
            this.filters = filters;
            this.webSocket = webSocket;
        }
    }

    private List<Message.MessageAttachment> parseAttachments(io.vertx.core.json.JsonArray attachmentsJson) {
        List<Message.MessageAttachment> attachments = new ArrayList<>();
        if (attachmentsJson == null || attachmentsJson.isEmpty()) {
            return attachments;
        }

        for (int i = 0; i < attachmentsJson.size(); i++) {
            Object raw = attachmentsJson.getValue(i);
            if (!(raw instanceof io.vertx.core.json.JsonObject)) {
                continue;
            }
            io.vertx.core.json.JsonObject attachmentJson = (io.vertx.core.json.JsonObject) raw;
            String typeString = attachmentJson.getString("type", "image");
            Message.MessageAttachment.AttachmentType type;
            try {
                type = Message.MessageAttachment.AttachmentType.valueOf(typeString.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = Message.MessageAttachment.AttachmentType.IMAGE;
            }
            String url = attachmentJson.getString("url");
            if (url == null || url.isEmpty()) {
                continue;
            }
            String preview = attachmentJson.getString("preview", url);
            attachments.add(new Message.MessageAttachment(type, url, preview));
        }
        return attachments;
    }

    private void broadcastToChat(String chatId, String type, JsonObject data) {
        try {
            Optional<Chat> chatOpt = chatService.getChatById(chatId);
            if (!chatOpt.isPresent()) {
                return;
            }

            com.tindapp.model.Chat chat = chatOpt.get();

            ServerWebSocket socket1 = userConnections.get(chat.getUser1Id());
            if (socket1 != null) {
                sendMessage(socket1, type, data);
            }

            ServerWebSocket socket2 = userConnections.get(chat.getUser2Id());
            if (socket2 != null) {
                sendMessage(socket2, type, data);
            }
        } catch (Exception e) {
            logger.error("Error broadcasting to chat", e);
        }
    }

    private void notifyOtherParticipants(String chatId, Long excludeUserId, String type, JsonObject data) {
        try {
            Optional<com.tindapp.model.Chat> chatOpt = chatService.getChatById(chatId);
            if (!chatOpt.isPresent()) {
                return;
            }

            com.tindapp.model.Chat chat = chatOpt.get();

            Long companionId = chat.getCompanionId(excludeUserId);
            if (companionId != null) {
                ServerWebSocket socket = userConnections.get(companionId);
                if (socket != null) {
                    sendMessage(socket, type, data);
                }
            }
        } catch (Exception e) {
            logger.error("Error notifying other participants", e);
        }
    }

    private void sendMessage(ServerWebSocket webSocket, String type, JsonObject data) {
        try {
            JsonObject message = new JsonObject()
                .put("type", type)
                .put("data", data)
                .put("timestamp", LocalDateTime.now().toString());

            webSocket.writeTextMessage(message.encode());
        } catch (Exception e) {
            logger.error("Error sending WebSocket message", e);
        }
    }

    private void sendError(ServerWebSocket webSocket, String error) {
        JsonObject errorData = new JsonObject()
            .put("error", error);
        sendMessage(webSocket, "error", errorData);
    }

    private void startHeartbeatTimer() {
        vertx.setPeriodic(30000, timerId -> {
            userConnections.values().forEach(socket -> {
                try {
                    sendMessage(socket, "ping", new JsonObject().put("timestamp", System.currentTimeMillis()));
                } catch (Exception e) {
                }
            });
        });
    }

    private void startTypingCleanup() {
        vertx.setPeriodic(AppConfig.TYPING_CLEANUP_INTERVAL, timerId -> {
            typingStatus.clear();
        });
    }

    public void sendNotificationToUser(Long userId, JsonObject notification) {
        ServerWebSocket socket = userConnections.get(userId);
        if (socket != null) {
            sendMessage(socket, "notification", notification);
        }
    }

    public void sendMessageToUser(Long userId, String type, JsonObject data) {
        ServerWebSocket socket = userConnections.get(userId);
        if (socket != null) {
            logger.info("Sending WebSocket message to user {}: type={}, data={}", userId, type, data.encode());
            sendMessage(socket, type, data);
        } else {
            logger.warn("Cannot send WebSocket message to user {}: user not connected", userId);
        }
    }

    public boolean isUserOnline(Long userId) {
        return userConnections.containsKey(userId);
    }

    public int getOnlineUsersCount() {
        return userConnections.size();
    }
}
