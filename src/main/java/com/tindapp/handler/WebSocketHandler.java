package com.tindapp.handler;

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
import com.tindapp.util.FutureUtils;
import com.tindapp.util.ResponseMapper;
import io.vertx.core.Future;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long SOCKET_IDLE_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * 3;

    private final Vertx vertx;
    private final ChatService chatService;
    private final MessageService messageService;
    private final UserService userService;
    private final TokenService tokenService;
    private final ProfileService profileService;
    private final NotificationService notificationService;

    private final Map<Long, Map<Integer, ServerWebSocket>> userConnections = new ConcurrentHashMap<>();
    private final Map<Integer, Long> socketToUser = new ConcurrentHashMap<>();
    private final Map<Integer, Long> socketLastSeen = new ConcurrentHashMap<>();
    private final Map<Long, String> userChats = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> typingStatus = new ConcurrentHashMap<>();
    private final Map<Long, ProfileSubscription> profileSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, ChatParticipants> chatParticipantsCache = new ConcurrentHashMap<>();

    public WebSocketHandler(final Vertx vertx,
                            final ChatService chatService,
                            final MessageService messageService,
                            final UserService userService,
                            final TokenService tokenService,
                            final ProfileService profileService,
                            final NotificationService notificationService) {
        this.vertx = vertx;
        this.chatService = chatService;
        this.messageService = messageService;
        this.userService = userService;
        this.tokenService = tokenService;
        this.profileService = profileService;
        this.notificationService = notificationService;
        startHeartbeatTimer();
        startTypingCleanup();
        startOnlineStatusCleanup();
    }

    public void handle(final ServerWebSocket webSocket) {
        if (!"/ws".equals(webSocket.path())) {
            webSocket.reject();
            return;
        }

        final String token = extractToken(webSocket.query());
        if (token == null) {
            logger.warn("WebSocket connection rejected: missing token");
            closeQuietly(webSocket);
            return;
        }

        tokenService.validateToken(token)
            .onSuccess(user -> {
                if (user == null || user.getId() == null) {
                    logger.warn("WebSocket connection rejected: invalid token");
                    closeQuietly(webSocket);
                    return;
                }
                establishConnection(webSocket, user);
            })
            .onFailure(error -> {
                logger.error("Error establishing WebSocket connection", error);
                closeQuietly(webSocket);
            });
    }

    private void establishConnection(final ServerWebSocket webSocket, final User user) {
        final Integer socketKey = webSocket.hashCode();
        registerConnection(user.getId(), socketKey, webSocket);

        userService.updateOnlineStatus(user.getId(), true)
            .onSuccess(ignored -> notifyProfileUpdated(user.getId()))
            .onFailure(error -> logger.warn("Failed to mark user {} online", user.getId(), error));

        webSocket.handler(buffer -> {
            try {
                handleWebSocketMessage(webSocket, new JsonObject(buffer.toString()));
            } catch (final Exception e) {
                logger.warn("Invalid WebSocket payload", e);
                sendError(webSocket, "Invalid message payload");
            }
        });
        webSocket.closeHandler(v -> handleWebSocketClose(webSocket));
        webSocket.exceptionHandler(error -> {
            logger.error("WebSocket exception", error);
            handleWebSocketClose(webSocket);
            closeQuietly(webSocket);
        });

        sendMessage(webSocket, "connected", new JsonObject().put("status", "connected"));
        logger.info("WebSocket connection established for user: id={}, vkId={}", user.getId(), user.getVkId());
    }

    private synchronized void registerConnection(final Long userId, final Integer socketKey, final ServerWebSocket webSocket) {
        userConnections.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>()).put(socketKey, webSocket);
        socketToUser.put(socketKey, userId);
        socketLastSeen.put(socketKey, System.currentTimeMillis());
    }

    private synchronized boolean unregisterConnection(final Long userId, final Integer socketKey) {
        final Map<Integer, ServerWebSocket> sockets = userConnections.get(userId);
        if (sockets == null) {
            return false;
        }

        sockets.remove(socketKey);
        if (sockets.isEmpty()) {
            userConnections.remove(userId);
            return false;
        }
        return true;
    }

    private synchronized List<ServerWebSocket> getUserSockets(final Long userId) {
        if (userId == null) {
            return List.of();
        }

        final Map<Integer, ServerWebSocket> sockets = userConnections.get(userId);
        if (sockets == null || sockets.isEmpty()) {
            return List.of();
        }
        return List.copyOf(sockets.values());
    }

    private synchronized List<SocketConnection> getAllConnections() {
        final List<SocketConnection> connections = new ArrayList<>();
        userConnections.values().forEach(sockets -> sockets.forEach((socketKey, socket) ->
            connections.add(new SocketConnection(socketKey, socket))));
        return connections;
    }

    private synchronized Set<Long> getConnectedUserIds() {
        return Set.copyOf(userConnections.keySet());
    }

    private void removeProfileSubscription(final Long userId, final ServerWebSocket webSocket) {
        profileSubscriptions.computeIfPresent(userId, (ignored, subscription) ->
            subscription.webSocket().equals(webSocket) ? null : subscription);
    }

    private String extractToken(final String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (final String part : query.split("&")) {
            if (part.startsWith("token=") && part.length() > 6) {
                return part.substring(6);
            }
        }
        return null;
    }

    private void handleWebSocketMessage(final ServerWebSocket webSocket, final JsonObject message) {
        socketLastSeen.put(webSocket.hashCode(), System.currentTimeMillis());

        final String type = message.getString("type");
        final JsonObject data = message.getJsonObject("data", new JsonObject());

        switch (type) {
            case "auth" -> handleAuth(webSocket);
            case "join_chat" -> handleJoinChat(webSocket, data);
            case "leave_chat" -> handleLeaveChat(webSocket, data);
            case "send_message" -> handleSendMessage(webSocket, data);
            case "typing" -> handleTyping(webSocket, data);
            case "read" -> handleReadReceipt(webSocket, data);
            case "ping" -> handlePing(webSocket);
            case "pong" -> {
            }
            case "start_search" -> handleStartCompanionSearch(webSocket, data);
            case "stop_search" -> handleStopCompanionSearch(webSocket);
            case "profiles_subscribe" -> handleProfilesSubscribe(webSocket, data);
            case "profiles_unsubscribe" -> handleProfilesUnsubscribe(webSocket);
            default -> {
                logger.warn("Unknown WebSocket message type: {}", type);
                sendError(webSocket, "Unknown message type");
            }
        }
    }

    private void handleAuth(final ServerWebSocket webSocket) {
        final Long userId = getUserId(webSocket);
        if (userId == null) {
            sendError(webSocket, "Not authenticated");
            return;
        }
        sendMessage(webSocket, "authenticated", new JsonObject()
            .put("userId", userId)
            .put("authenticated", true));
    }

    private void handleJoinChat(final ServerWebSocket webSocket, final JsonObject data) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }

        final String chatId = data.getString("chatId");
        if (chatId == null) {
            sendError(webSocket, "Missing chatId");
            return;
        }

        chatService.isUserInChat(chatId, userId)
            .compose(isParticipant -> {
                if (!isParticipant) {
                    return FutureUtils.failed("Access denied to chat");
                }
                return chatService.getChatById(chatId)
                    .compose(chatOpt -> chatOpt
                        .map(Future::succeededFuture)
                        .orElseGet(() -> FutureUtils.failed("Chat not found")));
            })
            .compose(chat -> leaveCurrentChatIfNeeded(userId, chatId).map(v -> chat))
            .onSuccess(chat -> {
                cacheChatParticipants(chat);
                userChats.put(userId, chatId);
                final Long companionId = chat.getCompanionId(userId);
                final boolean companionInChat = isUserActiveInChat(companionId, chatId);

                final JsonArray activeParticipants = new JsonArray().add(userId);
                if (companionInChat && companionId != null) {
                    activeParticipants.add(companionId);
                }

                sendMessage(webSocket, "chat_joined", new JsonObject()
                    .put("chatId", chatId)
                    .put("joined", true)
                    .put("userId", userId)
                    .put("companionId", companionId)
                    .put("companionInChat", companionInChat)
                    .put("activeParticipantIds", activeParticipants));

                notifyOtherParticipants(chatId, userId, "user_joined", new JsonObject()
                    .put("userId", userId)
                    .put("chatId", chatId));
            })
            .onFailure(error -> {
                logger.error("Error joining chat {}", chatId, error);
                sendError(webSocket, error.getMessage() != null ? error.getMessage() : "Failed to join chat");
            });
    }

    private void handleLeaveChat(final ServerWebSocket webSocket, final JsonObject data) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }

        final String requestedChatId = data.getString("chatId");
        final String chatId = requestedChatId != null ? requestedChatId : userChats.get(userId);
        if (chatId == null) {
            sendMessage(webSocket, "chat_left", new JsonObject().put("left", true));
            return;
        }

        leaveChat(userId, chatId)
            .onSuccess(v -> sendMessage(webSocket, "chat_left", new JsonObject()
                .put("chatId", chatId)
                .put("left", true)))
            .onFailure(error -> {
                logger.error("Error leaving chat {}", chatId, error);
                sendError(webSocket, "Failed to leave chat");
            });
    }

    private void handleSendMessage(final ServerWebSocket webSocket, final JsonObject data) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }

        final String chatId = data.getString("chatId");
        final String text = data.getString("text", "");
        final String replyToMessageId = data.getString("replyToMessageId");
        final String clientMessageId = data.getString("clientMessageId");
        final List<Message.MessageAttachment> attachments = parseAttachments(data.getJsonArray("attachments"));

        if (chatId == null) {
            sendError(webSocket, "Missing chatId");
            return;
        }
        if (text.trim().isEmpty() && attachments.isEmpty()) {
            sendError(webSocket, "Missing message content");
            return;
        }

        messageService.sendMessage(userId, chatId, text, replyToMessageId, attachments, clientMessageId)
            .onSuccess(message -> broadcastToChat(chatId, "message", ResponseMapper.toMessageResponse(message)))
            .onFailure(error -> {
                logger.error("Error sending message via WebSocket", error);
                sendError(webSocket, error.getMessage() != null ? error.getMessage() : "Failed to send message");
            });
    }

    private void handleTyping(final ServerWebSocket webSocket, final JsonObject data) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }

        final String chatId = data.getString("chatId");
        final boolean isTyping = data.getBoolean("isTyping", false);
        if (chatId == null) {
            sendError(webSocket, "Missing chatId");
            return;
        }

        typingStatus.put(userId, isTyping);
        notifyOtherParticipants(chatId, userId, "typing", new JsonObject()
            .put("userId", userId)
            .put("chatId", chatId)
            .put("isTyping", isTyping));

        if (isTyping) {
            vertx.setTimer(5000, timerId -> {
                typingStatus.remove(userId);
                notifyOtherParticipants(chatId, userId, "typing", new JsonObject()
                    .put("userId", userId)
                    .put("chatId", chatId)
                    .put("isTyping", false));
            });
        }
    }

    private void handleReadReceipt(final ServerWebSocket webSocket, final JsonObject data) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }

        final String chatId = data.getString("chatId");
        final String messageId = data.getString("messageId");
        if (chatId == null || messageId == null) {
            sendError(webSocket, "Missing chatId or messageId");
            return;
        }

        messageService.markMessagesAsRead(chatId, userId, List.of(messageId))
            .otherwise(error -> {
                logger.warn("Failed to mark message {} as read", messageId, error);
                return null;
            })
            .onComplete(ignored -> notifyOtherParticipants(chatId, userId, "read", new JsonObject()
                .put("chatId", chatId)
                .put("messageId", messageId)
                .put("userId", userId)
                .put("readAt", data.getString("readAt", LocalDateTime.now().toString()))));
    }

    private void handlePing(final ServerWebSocket webSocket) {
        sendMessage(webSocket, "pong", new JsonObject().put("timestamp", System.currentTimeMillis()));
    }

    private void handleStartCompanionSearch(final ServerWebSocket webSocket, final JsonObject data) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }

        if (chatService.isSearchingCompanion(userId)) {
            chatService.cancelCompanionSearch(userId);
        }

        final JsonObject filters = data.getJsonObject("filters", new JsonObject());
        final ChatService.SearchFilters searchFilters = new ChatService.SearchFilters(
            filters.getString("gender", "any"),
            filters.getJsonArray("ageRange", new JsonArray().add(18).add(80)).stream().mapToInt(o -> (Integer) o).toArray(),
            filters.getString("preference", "communication"),
            filters.getString("city")
        );

        chatService.findCompanion(userId, searchFilters)
            .onSuccess(findResult -> {
                if (findResult.inQueue()) {
                    sendMessage(webSocket, "search_queued", new JsonObject()
                        .put("inQueue", true)
                        .put("queueSize", findResult.queueSize())
                        .put("message", findResult.message()));
                    return;
                }

                if (findResult.matchResult() == null) {
                    return;
                }

                final ChatService.MatchResult match = findResult.matchResult();
                sendMessage(webSocket, "match_found", JsonObject.mapFrom(match));

                if (match.companion() != null && match.companion().id() != null) {
                    sendMessageToUser(match.companion().id(), "match_found", new JsonObject()
                        .put("chatId", match.chatId())
                        .put("cost", match.cost())
                        .put("companion", new JsonObject()
                            .put("id", userId)
                            .put("nickname", "Собеседник #" + userId)
                            .put("isVerified", false)
                            .put("isOnline", true)));
                }

                sendMatchNotifications(userId, match);
            })
            .onFailure(error -> {
                logger.error("Error starting companion search", error);
                sendError(webSocket, error.getMessage() != null ? error.getMessage() : "Failed to start search");
            });
    }

    private void handleStopCompanionSearch(final ServerWebSocket webSocket) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }

        try {
            final boolean stopped = chatService.cancelCompanionSearch(userId);
            sendMessage(webSocket, "search_stopped", new JsonObject()
                .put("stopped", stopped)
                .put("message", stopped ? "Search stopped" : "No active search"));
        } catch (final Exception e) {
            logger.error("Error stopping companion search for user {}", userId, e);
            sendError(webSocket, "Failed to stop search");
        }
    }

    private void handleProfilesSubscribe(final ServerWebSocket webSocket, final JsonObject data) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }

        userService.getUserById(userId)
            .compose(viewerOpt -> viewerOpt
                .map(Future::succeededFuture)
                .orElseGet(() -> FutureUtils.failed("User not found")))
            .onSuccess(viewer -> {
                final ProfileService.ProfileFilters filters = profileService.parseFilters(
                    data.getJsonObject("filters", new JsonObject()),
                    viewer
                );
                profileSubscriptions.put(userId, new ProfileSubscription(userId, filters, webSocket));
                sendMessage(webSocket, "profiles_subscribed", new JsonObject().put("filters", JsonObject.mapFrom(filters)));
            })
            .onFailure(error -> {
                logger.error("Error subscribing to profiles", error);
                sendError(webSocket, "Failed to subscribe to profiles");
            });
    }

    private void handleProfilesUnsubscribe(final ServerWebSocket webSocket) {
        final Long userId = getRequiredUserId(webSocket);
        if (userId == null) {
            return;
        }
        removeProfileSubscription(userId, webSocket);
        sendMessage(webSocket, "profiles_unsubscribed", new JsonObject().put("success", true));
    }

    private void handleWebSocketClose(final ServerWebSocket webSocket) {
        final Integer socketKey = webSocket.hashCode();
        final Long disconnectedUserId = socketToUser.remove(socketKey);
        socketLastSeen.remove(socketKey);
        if (disconnectedUserId == null) {
            return;
        }

        final boolean hasActiveConnections = unregisterConnection(disconnectedUserId, socketKey);
        removeProfileSubscription(disconnectedUserId, webSocket);
        if (hasActiveConnections) {
            return;
        }

        typingStatus.remove(disconnectedUserId);
        profileSubscriptions.remove(disconnectedUserId);
        chatService.cancelCompanionSearch(disconnectedUserId);

        final String activeChatId = userChats.remove(disconnectedUserId);
        if (activeChatId != null) {
            notifyOtherParticipants(activeChatId, disconnectedUserId, "user_left", new JsonObject()
                .put("userId", disconnectedUserId)
                .put("chatId", activeChatId));
        }

        userService.updateOnlineStatus(disconnectedUserId, false)
            .onSuccess(ignored -> notifyProfileUpdated(disconnectedUserId))
            .onFailure(error -> logger.warn("Failed to mark user {} offline", disconnectedUserId, error));
    }

    public void notifyProfileUpdated(final Long userId) {
        if (userId == null) {
            return;
        }
        userService.getUserById(userId)
            .map(optional -> optional.orElse(null))
            .onSuccess(this::notifyProfileUpdated)
            .onFailure(error -> logger.warn("Failed to load updated profile {}", userId, error));
    }

    public void notifyProfileUpdated(final User updatedUser) {
        if (updatedUser == null) {
            return;
        }

        final List<Future<Void>> notifications = profileSubscriptions.values().stream()
            .map(subscription -> userService.getUserById(subscription.userId())
                .map(optional -> optional.orElse(null))
                .compose(viewer -> {
                    if (viewer == null) {
                        return Future.succeededFuture();
                    }

                    final boolean matches = profileService.matchesFilters(viewer, updatedUser, subscription.filters());
                    final JsonObject payload = new JsonObject()
                        .put("profileId", updatedUser.getId())
                        .put("isMatch", matches);

                    if (!matches) {
                        sendMessage(subscription.webSocket(), "profiles_changed", payload);
                        return Future.succeededFuture();
                    }

                    return profileService.toProfileCard(viewer, updatedUser)
                        .map(card -> {
                            payload.put("profile", JsonObject.mapFrom(card));
                            sendMessage(subscription.webSocket(), "profiles_changed", payload);
                            return (Void) null;
                        });
                })
                .otherwise(error -> {
                    logger.error("Error notifying profile update for subscriber {}", subscription.userId(), error);
                    return null;
                }))
            .toList();

        FutureUtils.all(notifications)
            .onFailure(error -> logger.warn("Profile update broadcast completed with errors", error));
    }

    private void sendMatchNotifications(final Long currentUserId, final ChatService.MatchResult match) {
        if (notificationService == null || match == null) {
            return;
        }

        final List<Future<?>> futures = new ArrayList<>();
        futures.add(notificationService.sendMatchFoundNotification(currentUserId, "Собеседник"));
        if (match.companion() != null && match.companion().id() != null) {
            futures.add(notificationService.sendMatchFoundNotification(match.companion().id(), "Собеседник"));
        }

        FutureUtils.all(futures)
            .onFailure(error -> logger.warn("Failed to send match notifications", error));
    }

    public void notifyChatClosed(final String chatId,
                                 final Long closedByUserId,
                                 final Chat.ChatClosureReason reason,
                                 final String closedAt) {
        if (chatId == null) {
            return;
        }
        broadcastToChat(chatId, "chat_closed", new JsonObject()
            .put("chatId", chatId)
            .put("closedByUserId", closedByUserId)
            .put("reason", reason != null ? reason.name() : null)
            .put("closedAt", closedAt));
    }

    public void notifyChatReopened(final String chatId) {
        if (chatId == null) {
            return;
        }
        broadcastToChat(chatId, "chat_reopened", new JsonObject().put("chatId", chatId));
    }

    public void sendNotificationToUser(final Long userId, final JsonObject notification) {
        sendMessageToUser(userId, "notification", notification);
    }

    public void sendMessageToUser(final Long userId, final String type, final JsonObject data) {
        final JsonObject payload = data == null ? new JsonObject() : data;
        getUserSockets(userId).forEach(socket -> sendMessage(socket, type, payload.copy()));
    }

    public boolean isUserOnline(final Long userId) {
        return !getUserSockets(userId).isEmpty();
    }

    private Future<Void> leaveCurrentChatIfNeeded(final Long userId, final String newChatId) {
        final String previousChatId = userChats.get(userId);
        if (previousChatId == null || previousChatId.equals(newChatId)) {
            return Future.succeededFuture();
        }
        return leaveChat(userId, previousChatId);
    }

    private Future<Void> leaveChat(final Long userId, final String chatId) {
        userChats.remove(userId);
        return notifyOtherParticipants(chatId, userId, "user_left", new JsonObject()
            .put("userId", userId)
            .put("chatId", chatId));
    }

    private Future<Void> broadcastToChat(final String chatId, final String type, final JsonObject data) {
        return resolveChatParticipants(chatId).map(participants -> {
            if (participants == null) {
                return (Void) null;
            }

            sendMessageToUser(participants.user1Id(), type, data);
            sendMessageToUser(participants.user2Id(), type, data);
            return (Void) null;
        }).onFailure(error -> logger.error("Error broadcasting to chat {}", chatId, error));
    }

    private Future<Void> notifyOtherParticipants(final String chatId, final Long excludeUserId, final String type, final JsonObject data) {
        return resolveChatParticipants(chatId).map(participants -> {
            if (participants == null) {
                return (Void) null;
            }
            final Long companionId = participants.companionId(excludeUserId);
            if (companionId == null) {
                return (Void) null;
            }
            sendMessageToUser(companionId, type, data);
            return (Void) null;
        }).onFailure(error -> logger.error("Error notifying participants for chat {}", chatId, error));
    }

    private Future<ChatParticipants> resolveChatParticipants(final String chatId) {
        if (chatId == null) {
            return Future.succeededFuture((ChatParticipants) null);
        }

        final ChatParticipants cached = chatParticipantsCache.get(chatId);
        if (cached != null) {
            return Future.succeededFuture(cached);
        }

        return chatService.getChatById(chatId)
            .map(chatOpt -> {
                if (chatOpt.isEmpty()) {
                    return null;
                }
                cacheChatParticipants(chatOpt.get());
                return chatParticipantsCache.get(chatId);
            });
    }

    private void cacheChatParticipants(final Chat chat) {
        if (chat == null || chat.getId() == null || chat.getUser1Id() == null || chat.getUser2Id() == null) {
            return;
        }
        chatParticipantsCache.put(chat.getId(), new ChatParticipants(chat.getUser1Id(), chat.getUser2Id()));
    }

    private boolean isUserActiveInChat(final Long userId, final String chatId) {
        if (userId == null || chatId == null) {
            return false;
        }
        return chatId.equals(userChats.get(userId));
    }

    private Long getRequiredUserId(final ServerWebSocket webSocket) {
        final Long userId = getUserId(webSocket);
        if (userId == null) {
            sendError(webSocket, "Not authenticated");
            return null;
        }
        return userId;
    }

    private Long getUserId(final ServerWebSocket webSocket) {
        return socketToUser.get(webSocket.hashCode());
    }

    private List<Message.MessageAttachment> parseAttachments(final JsonArray attachmentsJson) {
        final List<Message.MessageAttachment> attachments = new ArrayList<>();
        if (attachmentsJson == null || attachmentsJson.isEmpty()) {
            return attachments;
        }

        for (int i = 0; i < attachmentsJson.size(); i++) {
            final Object raw = attachmentsJson.getValue(i);
            if (!(raw instanceof JsonObject attachmentJson)) {
                continue;
            }

            final String url = attachmentJson.getString("url");
            if (url == null || url.isBlank()) {
                continue;
            }

            Message.MessageAttachment.AttachmentType type;
            try {
                type = Message.MessageAttachment.AttachmentType.valueOf(
                    attachmentJson.getString("type", "image").toUpperCase()
                );
            } catch (final IllegalArgumentException e) {
                type = Message.MessageAttachment.AttachmentType.IMAGE;
            }

            attachments.add(new Message.MessageAttachment(type, url, attachmentJson.getString("preview", url)));
        }
        return attachments;
    }

    private void sendMessage(final ServerWebSocket webSocket, final String type, final JsonObject data) {
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.writeTextMessage(new JsonObject()
                .put("type", type)
                .put("data", data)
                .put("timestamp", LocalDateTime.now().toString())
                .encode());
        } catch (final Exception e) {
            logger.error("Error sending WebSocket message", e);
            handleWebSocketClose(webSocket);
            closeQuietly(webSocket);
        }
    }

    private void sendError(final ServerWebSocket webSocket, final String error) {
        sendMessage(webSocket, "error", new JsonObject().put("error", error));
    }

    private void startHeartbeatTimer() {
        vertx.setPeriodic(HEARTBEAT_INTERVAL_MS, timerId -> {
            final long cutoff = System.currentTimeMillis() - SOCKET_IDLE_TIMEOUT_MS;
            getAllConnections().forEach(connection -> {
                final Long lastSeenAt = socketLastSeen.get(connection.socketKey());
                if (lastSeenAt == null || lastSeenAt < cutoff) {
                    logger.info("Closing stale WebSocket connection: socketKey={}", connection.socketKey());
                    handleWebSocketClose(connection.webSocket());
                    closeQuietly(connection.webSocket());
                    return;
                }
                sendMessage(connection.webSocket(), "ping", new JsonObject().put("timestamp", System.currentTimeMillis()));
            });
            refreshOnlineStatuses(getConnectedUserIds());
        });
    }

    private void startTypingCleanup() {
        vertx.setPeriodic(AppConfig.TYPING_CLEANUP_INTERVAL, timerId -> typingStatus.clear());
    }

    private void startOnlineStatusCleanup() {
        vertx.setPeriodic(AppConfig.ONLINE_STATUS_CLEANUP_INTERVAL_MS, timerId ->
            userService.markStaleOnlineUsersOffline(AppConfig.ONLINE_STATUS_TTL)
                .onFailure(error -> logger.warn("Failed to cleanup stale online statuses", error)));
    }

    private void refreshOnlineStatuses(final Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        userService.refreshOnlineUsers(userIds)
            .onFailure(error -> logger.warn("Failed to refresh online statuses", error));
    }

    private void closeQuietly(final ServerWebSocket webSocket) {
        try {
            webSocket.close();
        } catch (final Exception ignored) {
        }
    }

    private record ProfileSubscription(Long userId, ProfileService.ProfileFilters filters, ServerWebSocket webSocket) {
    }

    private record SocketConnection(Integer socketKey, ServerWebSocket webSocket) {
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
}
