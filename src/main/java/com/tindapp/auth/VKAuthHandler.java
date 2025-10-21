package com.tindapp.auth;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class VKAuthHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(VKAuthHandler.class);
    private static final String ENCODING = "UTF-8";
    private static final String VK_USER_ID_PARAM = "vk_user_id";
    private static final String SIGN_PARAM = "sign";

    private final String clientSecret;

    public VKAuthHandler(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    @Override
    public void handle(RoutingContext context) {
        try {
            String query = context.request().query();
            String authHeader = context.request().getHeader("Authorization");

            Map<String, String> params;
            if (query != null && !query.isEmpty()) {
                params = parseQueryString(query);
            } else if (authHeader != null && authHeader.startsWith("VK ")) {
                params = parseQueryString(authHeader.substring(3));
            } else {
                sendUnauthorized(context, "Missing VK parameters");
                return;
            }

            if (!params.containsKey(VK_USER_ID_PARAM) || !params.containsKey(SIGN_PARAM)) {
                sendUnauthorized(context, "Missing required VK parameters");
                return;
            }

            if (!validateSignature(params)) {
                sendUnauthorized(context, "Invalid VK signature");
                return;
            }

            JsonObject vkUser = extractUserData(params);
            context.put("vkUser", vkUser);
            context.put("userId", vkUser.getLong("vk_user_id"));

            context.next();

        } catch (Exception e) {
            logger.error("VK authentication error", e);
            sendUnauthorized(context, "Authentication error");
        }
    }

    private Map<String, String> parseQueryString(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key = idx > 0 ? decode(pair.substring(0, idx)) : pair;
            String value = idx > 0 && pair.length() > idx + 1 ? decode(pair.substring(idx + 1)) : null;
            result.put(key, value);
        }

        return result;
    }

    private boolean validateSignature(Map<String, String> params) {
        try {
            String checkString = params.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("vk_"))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> encode(entry.getKey()) + "=" + (entry.getValue() == null ? "" : encode(entry.getValue())))
                    .collect(Collectors.joining("&"));

            String expectedSign = getHashCode(checkString, clientSecret);
            String actualSign = params.get(SIGN_PARAM);

            return expectedSign.equals(actualSign);
        } catch (Exception e) {
            logger.error("Error validating VK signature", e);
            return false;
        }
    }

    private String getHashCode(String data, String key) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(ENCODING), "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(secretKey);
        byte[] hmacData = mac.doFinal(data.getBytes(ENCODING));
        return new String(Base64.getUrlEncoder().withoutPadding().encode(hmacData));
    }

    private JsonObject extractUserData(Map<String, String> params) {
        JsonObject userData = new JsonObject();

        putIfPresent(userData, "vk_user_id", params.get("vk_user_id"), Long::parseLong);
        putIfPresent(userData, "vk_app_id", params.get("vk_app_id"), Long::parseLong);
        putIfPresent(userData, "vk_is_app_user", params.get("vk_is_app_user"), s -> "1".equals(s));
        putIfPresent(userData, "vk_are_notifications_enabled", params.get("vk_are_notifications_enabled"), s -> "1".equals(s));
        putIfPresent(userData, "vk_language", params.get("vk_language"), String::valueOf);
        putIfPresent(userData, "vk_platform", params.get("vk_platform"), String::valueOf);
        putIfPresent(userData, "vk_access_token_settings", params.get("vk_access_token_settings"), String::valueOf);

        putIfPresent(userData, "vk_group_id", params.get("vk_group_id"), Long::parseLong);
        putIfPresent(userData, "vk_viewer_group_role", params.get("vk_viewer_group_role"), String::valueOf);
        putIfPresent(userData, "vk_ts", params.get("vk_ts"), Long::parseLong);

        return userData;
    }

    private <T> void putIfPresent(JsonObject json, String key, String value, java.util.function.Function<String, T> converter) {
        if (value != null && !value.isEmpty()) {
            try {
                json.put(key, converter.apply(value));
            } catch (Exception e) {
                logger.warn("Failed to convert parameter {}: {}", key, value, e);
            }
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, ENCODING);
        } catch (UnsupportedEncodingException e) {
            logger.error("Failed to decode value: " + value, e);
            return value;
        }
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, ENCODING);
        } catch (UnsupportedEncodingException e) {
            logger.error("Failed to encode value: " + value, e);
            return value;
        }
    }

    private void sendUnauthorized(RoutingContext context, String message) {
        JsonObject error = new JsonObject()
                .put("success", false)
                .put("error", message);

        context.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(error.encode());
    }
}
