package com.tindapp.auth;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.service.TokenService;
import com.tindapp.service.UserService;
import com.tindapp.util.LanguageUtils;
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
import java.util.Optional;
import java.util.stream.Collectors;

public class AuthHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(AuthHandler.class);
    private static final String ENCODING = "UTF-8";
    private static final String VK_USER_ID_PARAM = "vk_user_id";
    private static final String SIGN_PARAM = "sign";

    public enum ErrorCodes {
        UNAUTHORIZED("UNAUTHORIZED"),
        VALIDATION_ERROR("VALIDATION_ERROR"),
        SERVER_ERROR("SERVER_ERROR");

        private final String code;

        ErrorCodes(final String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final String clientSecret;
    private final UserService userService;
    private final TokenService tokenService;

    public AuthHandler(final String clientSecret, final UserService userService, final TokenService tokenService) {
        this.clientSecret = clientSecret;
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @Override
    public void handle(final RoutingContext context) {
        try {
            final String query = context.request().query();

            if (query == null || query.isEmpty()) {
                sendError(context, 400, ErrorCodes.VALIDATION_ERROR, "Missing VK parameters");
                return;
            }

            final Map<String, String> params = parseQueryString(query);

            if (!params.containsKey(VK_USER_ID_PARAM) || !params.containsKey(SIGN_PARAM)) {
                sendError(context, 400, ErrorCodes.VALIDATION_ERROR, "Missing required VK parameters");
                return;
            }

            if (!validateSignature(params)) {
                sendError(context, 401, ErrorCodes.UNAUTHORIZED, "Invalid VK signature");
                return;
            }

            final JsonObject vkUserData = extractUserData(params);
            final Long vkUserId = vkUserData.getLong("vk_user_id");

            final User user = findOrCreateUser(vkUserData);

            if (user.getId() == null) {
                throw new RuntimeException("User ID is null after creation");
            }

            final String token = tokenService.createToken(user);

            final JsonObject response = new JsonObject()
                .put("success", true)
                .put("token", token)
                .put("userId", user.getId())
                .put("user", createUserResponse(user))
                .put("expiresIn", 24 * 60 * 60); // 24 часа в секундах

            logger.info("User authenticated successfully: vkId={}, token created", vkUserId);

            context.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(response.encode());

        } catch (final Exception e) {
            logger.error("Authentication error", e);
            sendError(context, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    private Map<String, String> parseQueryString(final String query) {
        final Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }

        final String[] pairs = query.split("&");
        for (final String pair : pairs) {
            final int idx = pair.indexOf('=');
            final String key = idx > 0 ? decode(pair.substring(0, idx)) : pair;
            final String value = idx > 0 && pair.length() > idx + 1 ? decode(pair.substring(idx + 1)) : null;
            result.put(key, value);
        }

        return result;
    }

    private boolean validateSignature(final Map<String, String> params) {
        try {
            final String checkString = params.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("vk_"))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + '=' + (entry.getValue() == null ? "" : encode(entry.getValue())))
                .collect(Collectors.joining("&"));

            final String expectedSign = getHashCode(checkString, clientSecret);
            final String actualSign = params.get(SIGN_PARAM);

            return expectedSign.equals(actualSign);
        } catch (final Exception e) {
            logger.error("Error validating VK signature", e);
            return false;
        }
    }

    private String getHashCode(final String data, final String key) throws Exception {
        final SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(ENCODING), "HmacSHA256");
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(secretKey);
        final byte[] hmacData = mac.doFinal(data.getBytes(ENCODING));
        return new String(Base64.getUrlEncoder().withoutPadding().encode(hmacData));
    }

    private JsonObject extractUserData(final Map<String, String> params) {
        final JsonObject userData = new JsonObject();

        putIfPresent(userData, "vk_user_id", params.get("vk_user_id"), Long::parseLong);
        putIfPresent(userData, "vk_app_id", params.get("vk_app_id"), Long::parseLong);
        putIfPresent(userData, "vk_is_app_user", params.get("vk_is_app_user"), s -> "1".equals(s));
        putIfPresent(userData, "vk_are_notifications_enabled", params.get("vk_are_notifications_enabled"), s -> "1".equals(s));
        putIfPresent(userData, "vk_language", params.get("vk_language"), String::valueOf);
        putIfPresent(userData, "vk_platform", params.get("vk_platform"), String::valueOf);
        putIfPresent(userData, "vk_access_token_settings", params.get("vk_access_token_settings"), String::valueOf);
        putIfPresent(userData, "first_name", params.get("first_name"), String::valueOf);
        putIfPresent(userData, "last_name", params.get("last_name"), String::valueOf);
        putIfPresent(userData, "photo_max_orig", params.get("photo_max_orig"), String::valueOf);
        putIfPresent(userData, "photo_100", params.get("photo_100"), String::valueOf);
        putIfPresent(userData, "sex", params.get("sex"), Integer::parseInt);

        putIfPresent(userData, "vk_group_id", params.get("vk_group_id"), Long::parseLong);
        putIfPresent(userData, "vk_viewer_group_role", params.get("vk_viewer_group_role"), String::valueOf);
        putIfPresent(userData, "vk_ts", params.get("vk_ts"), Long::parseLong);

        return userData;
    }

    private User findOrCreateUser(final JsonObject vkUserData) {
        final Long vkUserId = vkUserData.getLong("vk_user_id");

        final Optional<User> existingUser = userService.getUserByVkId(vkUserId);

        if (existingUser.isPresent()) {
            final User user = existingUser.get();
            user.setLastSeenDateTime(java.time.LocalDateTime.now());
            user.setOnline(true);
            userService.updateUser(user);
            return user;
        } else {
            final User newUser = new User();
            newUser.setVkId(vkUserId);
            newUser.setAge(18); // По умолчанию, нужно будет обновить из профиля VK
            newUser.setFirstName("");
            newUser.setLastName("");
            newUser.setAvatarUrl("");
            newUser.setCountry("");
            newUser.setCity(""); // Будет заполнено из VK данных
            newUser.setVerified(false);
            newUser.setOnline(true);
            newUser.setLastSeenDateTime(java.time.LocalDateTime.now());
            newUser.setBio("");
            newUser.setVisible(true);
            newUser.setBalance(AppConfig.INITIAL_USER_BALANCE); // Стартовый баланс
            newUser.setCreatedAtDateTime(java.time.LocalDateTime.now());
            newUser.setUpdatedAtDateTime(java.time.LocalDateTime.now());
            newUser.setNativeLanguage(LanguageUtils.normalizeLanguage(vkUserData.getString("vk_language")));
            applyVkProfileData(newUser, vkUserData);

            final User createdUser = userService.createUser(newUser);
            logger.info("New user created: vkId={}", vkUserId);
            return createdUser;
        }
    }

    private JsonObject createUserResponse(final User user) {
        final JsonObject response = new JsonObject()
            .put("id", user.getId())
            .put("vkId", user.getVkId())
            .put("age", user.getAge())
            .put("firstName", user.getFirstName())
            .put("lastName", user.getLastName())
            .put("avatarUrl", user.getAvatarUrl())
            .put("country", user.getCountry())
            .put("city", user.getCity())
            .put("isVerified", user.isVerified())
            .put("wasVerified", user.wasVerified())
            .put("isOnline", user.isOnline())
            .put("lastSeen", user.getLastSeen())
            .put("bio", user.getBio())
            .put("gender", user.getGender())
            .put("isVisible", user.isVisible())
            .put("balance", user.getBalance())
            .put("createdAt", user.getCreatedAt())
            .put("updatedAt", user.getUpdatedAt());

        final JsonObject subscription = new JsonObject()
            .put("isActive", false): получить из subscription service
            .put("type", "basic");
        response.put("subscription", subscription);

        final JsonObject settings = new JsonObject()
            .put("showAge", true)
            .put("showCity", true)
            .put("allowMessages", true)
            .put("allowCommunityMessages", false)
            .put("notifyAnonMessages", true)
            .put("notifyAnonDialogClosed", true)
            .put("notifyProfileNewChat", true)
            .put("notifyProfileMessages", true)
            .put("notifyProfileDialogClosed", true)
            .put("notifySubscriptionProblems", true);
        response.put("settings", settings);

        return response;
    }

    private <T> void putIfPresent(final JsonObject json, final String key, final String value, final java.util.function.Function<String, T> converter) {
        if (value != null && !value.isEmpty()) {
            try {
                json.put(key, converter.apply(value));
            } catch (final Exception e) {
                logger.warn("Failed to convert parameter {}: {}", key, value, e);
            }
        }
    }

    private void applyVkProfileData(final User user, final JsonObject vkUserData) {
        if (vkUserData == null) {
            return;
        }

        final String firstName = firstNonEmpty(
            vkUserData.getString("first_name"),
            vkUserData.getString("vk_first_name")
        );
        if (firstName != null && isBlank(user.getFirstName())) {
            user.setFirstName(firstName.trim());
        }

        final String lastName = firstNonEmpty(
            vkUserData.getString("last_name"),
            vkUserData.getString("vk_last_name")
        );
        if (lastName != null && isBlank(user.getLastName())) {
            user.setLastName(lastName.trim());
        }

        final String avatarUrl = firstNonEmpty(
            vkUserData.getString("photo_max_orig"),
            vkUserData.getString("photo_100"),
            vkUserData.getString("vk_profile_photo")
        );
        if (avatarUrl != null && isBlank(user.getAvatarUrl())) {
            final String mirrored = userService.mirrorExternalAvatar(avatarUrl.trim());
            if (mirrored != null) {
                user.setAvatarUrl(mirrored);
            }
        }

        Integer sex = vkUserData.getInteger("sex");
        if (sex == null) {
            sex = vkUserData.getInteger("vk_sex");
        }
        if (sex != null && user.getGenderEnum() == null) {
            switch (sex) {
                case 1:
                    user.setGender("female");
                    break;
                case 2:
                    user.setGender("male");
                    break;
                default:
                    user.setGender("other");
                    break;
            }
        }
    }

    private String firstNonEmpty(final String... values) {
        if (values == null) {
            return null;
        }
        for (final String value : values) {
            if (value != null) {
                final String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private boolean isBlank(final String value) {
        return value == null || value.trim().isEmpty();
    }

    private String decode(final String value) {
        try {
            return URLDecoder.decode(value, ENCODING);
        } catch (final UnsupportedEncodingException e) {
            logger.error("Failed to decode value: " + value, e);
            return value;
        }
    }

    private String encode(final String value) {
        try {
            return URLEncoder.encode(value, ENCODING);
        } catch (final UnsupportedEncodingException e) {
            logger.error("Failed to encode value: " + value, e);
            return value;
        }
    }

    private void sendError(final RoutingContext context, final int statusCode, final ErrorCodes errorCode, final String message) {
        final JsonObject error = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("code", errorCode.getCode());

        context.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(error.encode());
    }
}
