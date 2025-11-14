package com.tindapp.service;

import com.tindapp.config.AppConfig;
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
                    .put("color", "primary")
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

    public VkGroupNotificationService(String accessToken, long groupId) {
        this.httpClient = HttpClient.newHttpClient();
        this.accessToken = accessToken;
        this.groupId = groupId;
    }

    public VkSendResult sendMessage(Long vkUserId, String message) {
        if (vkUserId == null) {
            logger.warn("Cannot send VK notification: vkUserId is null");
            return VkSendResult.FAILED;
        }
        if (message == null || message.isBlank()) {
            logger.warn("Cannot send VK notification: message is blank");
            return VkSendResult.FAILED;
        }

        try {
            long randomId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            String payload = buildPayload(vkUserId, message, randomId);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body == null || body.isBlank()) {
                logger.warn("Empty response from VK API when sending message");
                return VkSendResult.FAILED;
            }

            JsonObject json = new JsonObject(body);
            if (json.containsKey("error")) {
                JsonObject error = json.getJsonObject("error");
                int errorCode = error.getInteger("error_code", -1);
                String errorMessage = error.getString("error_msg", "Unknown error");
                logger.warn("VK API error (code={}): {}", errorCode, errorMessage);

                if (PERMISSION_ERRORS.contains(errorCode)) {
                    return VkSendResult.PERMISSION_ERROR;
                }
                return VkSendResult.FAILED;
            }

            if (json.containsKey("response")) {
                return VkSendResult.SUCCESS;
            }

            logger.warn("Unexpected VK API response: {}", body);
            return VkSendResult.FAILED;
        } catch (Exception e) {
            logger.error("Failed to send VK community notification", e);
            return VkSendResult.FAILED;
        }
    }

    private String buildPayload(Long vkUserId, String message, long randomId) {
        StringBuilder sb = new StringBuilder();
        appendParam(sb, "user_id", vkUserId.toString());
        appendParam(sb, "random_id", String.valueOf(randomId));
        appendParam(sb, "message", message);
        appendParam(sb, "group_id", String.valueOf(groupId));
        appendParam(sb, "access_token", accessToken);
        appendParam(sb, "v", AppConfig.VK_API_VERSION);
        appendParam(sb, "keyboard", INLINE_KEYBOARD);
        return sb.toString();
    }

    private void appendParam(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(key)
            .append('=')
            .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
