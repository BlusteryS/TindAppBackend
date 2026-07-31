package com.tindapp.config;

import java.time.Duration;
import java.util.Set;

public final class AppConfig {

    private AppConfig() {
    }

    public static final int HTTP_PORT = Environment.requireInt("HTTP_PORT");
    public static final String HTTP_HOST = Environment.require("HTTP_HOST");

    public static final String TOKEN_SECRET = Environment.require("TOKEN_SECRET");

    public static final String VK_CLIENT_SECRET = Environment.require("VK_CLIENT_SECRET");

    public static final boolean ANONYMOUS_CHAT_ENABLED = true;
    public static final boolean PROFILES_ENABLED = true;
    public static final boolean SUBSCRIPTIONS_ENABLED = true;
    public static final boolean VERIFICATION_ENABLED = true;

    public static final int FREE_CHAT_ONLINE_THRESHOLD = 10;
    public static final int CHAT_COST_PER_ONLINE_USER = 10;
    public static final int MESSAGES_COST = 0;

    public static final int MAX_CHATS_PER_USER = 10;
    public static final int MAX_MESSAGES_PER_DAY = 1000;
    public static final int MAX_MESSAGE_LENGTH = 1000;
    public static final int MAX_BIO_LENGTH = 500;

    public static final int INITIAL_USER_BALANCE = 0;
    public static final int AD_REWARD_AMOUNT = 20;
    public static final int SUBSCRIPTION_REWARD_AMOUNT = 200;

    public static final int VK_PAY_COIN_RATE = 100; // 1 рубль = 100 фиан
    public static final int VOTES_COIN_RATE = 10;   // 1 голос = 10 фиан

    // Presence is refreshed by the authenticated SSE heartbeat.
    public static final Duration ONLINE_STATUS_TTL = Duration.ofMinutes(3);
    public static final long ONLINE_STATUS_CLEANUP_INTERVAL_MS = Duration.ofMinutes(1).toMillis();
    public static final Duration EXTERNAL_HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(4);
    public static final Duration EXTERNAL_HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(8);

    public static final String[] ALLOWED_ORIGINS = Environment.requireList("CORS_ALLOWED_ORIGINS");
    public static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT", "DELETE", "OPTIONS"};
    public static final String[] ALLOWED_HEADERS = {"Content-Type", "Authorization"};

    public static final String UPLOAD_DIR = "uploads";
    public static final long MAX_UPLOAD_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    public static final String APP_VERSION = "1.0.0";
    public static final String APP_NAME = "TindApp";

    public static final long VK_COMMUNITY_GROUP_ID = Environment.requireLong("VK_COMMUNITY_GROUP_ID");
    public static final String VK_COMMUNITY_ACCESS_TOKEN = Environment.require("VK_COMMUNITY_ACCESS_TOKEN");
    public static final String VK_API_VERSION = "5.199";
    public static final int VK_APP_ID = Environment.requireInt("VK_APP_ID");
    public static final String TRANSLATION_API_URL = Environment.require("TRANSLATION_API_URL");
    public static final String TRANSLATION_API_KEY = Environment.require("TRANSLATION_API_KEY");
    public static final String SUBSCRIPTION_PHOTO_URL = Environment.require("SUBSCRIPTION_PHOTO_URL");
    public static final Set<Long> ADMIN_VK_IDS = Environment.requireLongSet("ADMIN_VK_IDS");

    public static io.vertx.core.json.JsonObject getFeaturesConfig() {
        return new io.vertx.core.json.JsonObject()
            .put("anonymousChat", ANONYMOUS_CHAT_ENABLED)
            .put("profiles", PROFILES_ENABLED)
            .put("subscriptions", SUBSCRIPTIONS_ENABLED)
            .put("verification", VERIFICATION_ENABLED);
    }

    public static io.vertx.core.json.JsonObject getCostsConfig() {
        return new io.vertx.core.json.JsonObject()
            .put("anonymousChatCreation", CHAT_COST_PER_ONLINE_USER)
            .put("freeChatOnlineThreshold", FREE_CHAT_ONLINE_THRESHOLD)
            .put("chatCostPerOnlineUser", CHAT_COST_PER_ONLINE_USER)
            .put("messagesCost", MESSAGES_COST);
    }

    public static io.vertx.core.json.JsonObject getLimitsConfig() {
        return new io.vertx.core.json.JsonObject()
            .put("maxChatsPerUser", MAX_CHATS_PER_USER)
            .put("maxMessagesPerDay", MAX_MESSAGES_PER_DAY)
            .put("maxMessageLength", MAX_MESSAGE_LENGTH)
            .put("maxBioLength", MAX_BIO_LENGTH);
    }

    public static io.vertx.core.json.JsonObject getAppInfo() {
        return new io.vertx.core.json.JsonObject()
            .put("version", APP_VERSION)
            .put("name", APP_NAME);
    }

    public static io.vertx.core.json.JsonObject getClientConfig() {
        return new io.vertx.core.json.JsonObject()
            .put("features", getFeaturesConfig())
            .put("costs", getCostsConfig())
            .put("limits", getLimitsConfig());
    }
}
