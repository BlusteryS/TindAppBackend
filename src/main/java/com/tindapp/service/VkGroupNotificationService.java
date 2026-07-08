package com.tindapp.service;

import com.tindapp.config.AppConfig;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class VkGroupNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(VkGroupNotificationService.class);
    private static final String API_URL = "https://api.vk.com/method/messages.send";
    private static final Set<Integer> PERMISSION_ERRORS = Set.of(5, 901, 902, 917, 934);
    private static final String INLINE_KEYBOARD = new JsonObject()
        .put("inline", true)
        .put("buttons", List.of(
            List.of(
                new JsonObject()
                    .put("action", new JsonObject()
                        .put("type", "open_app")
                        .put("app_id", AppConfig.VK_APP_ID)
                        .put("owner_id", AppConfig.VK_COMMUNITY_GROUP_ID)
                        .put("label", "Открыть TindApp")
                        .put("hash", "from=vk_notifications"))
            )
        ))
        .encode();

    public enum VkSendResult {
        SUCCESS,
        PERMISSION_ERROR,
        FAILED
    }

    private final HttpClient httpClient;
    private final String accessToken;
    private final long groupId;

    public VkGroupNotificationService(final String accessToken, final long groupId) {
        httpClient = HttpClient.newHttpClient();
        this.accessToken = accessToken;
        this.groupId = groupId;
    }

    public Future<VkSendResult> sendMessage(final Long vkUserId, final String message) {
        if (vkUserId == null || message == null || message.isBlank()) {
            return Future.succeededFuture(VkSendResult.FAILED);
        }

        final long randomId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(buildPayload(vkUserId, message, randomId)))
            .build();

        return Future.fromCompletionStage(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .map(response -> parseResponse(response.body()))
            .otherwise(error -> {
                logger.error("Failed to send VK community notification", error);
                return VkSendResult.FAILED;
            });
    }

    private VkSendResult parseResponse(final String body) {
        if (body == null || body.isBlank()) {
            logger.warn("Empty response from VK API when sending message");
            return VkSendResult.FAILED;
        }

        final JsonObject json = new JsonObject(body);
        if (json.containsKey("error")) {
            final JsonObject error = json.getJsonObject("error");
            final int errorCode = error.getInteger("error_code", -1);
            logger.warn("VK API error (code={}): {}", errorCode, error.getString("error_msg", "Unknown error"));
            return PERMISSION_ERRORS.contains(errorCode) ? VkSendResult.PERMISSION_ERROR : VkSendResult.FAILED;
        }

        if (json.containsKey("response")) {
            return VkSendResult.SUCCESS;
        }

        logger.warn("Unexpected VK API response: {}", body);
        return VkSendResult.FAILED;
    }

    private String buildPayload(final Long vkUserId, final String message, final long randomId) {
        final StringBuilder sb = new StringBuilder();
        appendParam(sb, "user_id", vkUserId.toString());
        appendParam(sb, "random_id", String.valueOf(randomId));
        appendParam(sb, "message", message);
        appendParam(sb, "group_id", String.valueOf(groupId));
        appendParam(sb, "access_token", accessToken);
        appendParam(sb, "v", AppConfig.VK_API_VERSION);
        appendParam(sb, "keyboard", INLINE_KEYBOARD);
        return sb.toString();
    }

    private void appendParam(final StringBuilder sb, final String key, final String value) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
