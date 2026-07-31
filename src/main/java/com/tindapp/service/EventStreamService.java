package com.tindapp.service;

import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class EventStreamService {

    private static final Logger logger = LoggerFactory.getLogger(EventStreamService.class);
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;

    private final UserService userService;
    private final Map<Long, Map<String, HttpServerResponse>> connections = new ConcurrentHashMap<>();
    private final Map<Long, ChatPresence> chatPresence = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();

    public EventStreamService(final io.vertx.core.Vertx vertx, final UserService userService) {
        this.userService = userService;
        vertx.setPeriodic(HEARTBEAT_INTERVAL_MS, timerId -> sendHeartbeat());
    }

    public void open(final RoutingContext ctx, final Long userId) {
        final HttpServerResponse response = ctx.response();
        response
            .setChunked(true)
            .putHeader("Content-Type", "text/event-stream; charset=utf-8")
            .putHeader("Cache-Control", "no-cache, no-transform")
            .putHeader("Connection", "keep-alive")
            .putHeader("X-Accel-Buffering", "no");

        final String connectionId = UUID.randomUUID().toString();
        connections.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>()).put(connectionId, response);

        response.closeHandler(ignored -> close(userId, connectionId));
        response.exceptionHandler(error -> {
            close(userId, connectionId);
        });

        response.write(": connected\n\n");
        userService.updateOnlineStatus(userId, true)
            .onSuccess(ignored -> broadcast("profiles_changed", new JsonObject()
                .put("profileId", userId)
                .put("isOnline", true)))
            .onFailure(error -> logger.warn("Failed to mark SSE user {} online", userId, error));
    }

    public void publishToUser(final Long userId, final String event, final JsonObject data) {
        if (userId == null) {
            return;
        }
        final Map<String, HttpServerResponse> userConnections = connections.get(userId);
        if (userConnections == null || userConnections.isEmpty()) {
            return;
        }
        final String payload = formatEvent(event, data);
        userConnections.forEach((connectionId, response) -> write(userId, connectionId, response, payload));
    }

    public void publishToUsers(final Collection<Long> userIds, final String event, final JsonObject data) {
        if (userIds == null) {
            return;
        }
        userIds.stream().distinct().forEach(userId -> publishToUser(userId, event, data));
    }

    public void broadcast(final String event, final JsonObject data) {
        connections.keySet().forEach(userId -> publishToUser(userId, event, data));
    }

    public void setChatPresence(final Long userId, final String chatId, final Long companionId, final boolean active) {
        final ChatPresence previous = active
            ? chatPresence.put(userId, new ChatPresence(chatId, companionId))
            : chatPresence.remove(userId);

        if (previous != null && (!active || !previous.chatId().equals(chatId))) {
            publishChatPresence(userId, previous, false);
        }
        if (active) {
            final ChatPresence companionPresence = chatPresence.get(companionId);
            final boolean companionActive = companionPresence != null
                && chatId.equals(companionPresence.chatId())
                && userId.equals(companionPresence.companionId());
            publishToUser(userId, "chat_presence", new JsonObject()
                .put("chatId", chatId)
                .put("userId", companionId)
                .put("active", companionActive));
            publishChatPresence(userId, new ChatPresence(chatId, companionId), true);
        }
    }

    private void publishChatPresence(final Long userId, final ChatPresence presence, final boolean active) {
        publishToUser(presence.companionId(), "chat_presence", new JsonObject()
            .put("chatId", presence.chatId())
            .put("userId", userId)
            .put("active", active));
    }

    private String formatEvent(final String event, final JsonObject data) {
        return "id: " + eventSequence.incrementAndGet() + '\n'
            + "event: " + event + '\n'
            + "data: " + (data != null ? data.encode() : "{}") + "\n\n";
    }

    private void write(final Long userId, final String connectionId, final HttpServerResponse response, final String payload) {
        if (response.ended() || response.closed()) {
            close(userId, connectionId);
            return;
        }
        response.write(payload).onFailure(error -> close(userId, connectionId));
    }

    private void sendHeartbeat() {
        connections.forEach((userId, userConnections) ->
            userConnections.forEach((connectionId, response) -> write(userId, connectionId, response, ": heartbeat\n\n")));
        userService.refreshOnlineUsers(connections.keySet())
            .onFailure(error -> logger.warn("Failed to refresh SSE online statuses", error));
    }

    private void close(final Long userId, final String connectionId) {
        final Map<String, HttpServerResponse> userConnections = connections.get(userId);
        if (userConnections == null) {
            return;
        }
        userConnections.remove(connectionId);
        if (!userConnections.isEmpty()) {
            return;
        }

        connections.remove(userId, userConnections);
        final ChatPresence presence = chatPresence.remove(userId);
        if (presence != null) {
            publishChatPresence(userId, presence, false);
        }
        userService.updateOnlineStatus(userId, false)
            .onSuccess(ignored -> broadcast("profiles_changed", new JsonObject()
                .put("profileId", userId)
                .put("isOnline", false)))
            .onFailure(error -> logger.warn("Failed to mark SSE user {} offline", userId, error));
    }

    private record ChatPresence(String chatId, Long companionId) {
    }
}
