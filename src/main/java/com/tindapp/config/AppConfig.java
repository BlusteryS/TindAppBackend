package com.tindapp.config;

public class AppConfig {

    public static final int HTTP_PORT = 8012;
    public static final String HTTP_HOST = "0.0.0.0";

    public static final String VK_CLIENT_SECRET = System.getenv("VK_CLIENT_SECRET");

    public static final boolean ANONYMOUS_CHAT_ENABLED = true;
    public static final boolean PROFILES_ENABLED = true;
    public static final boolean SUBSCRIPTIONS_ENABLED = true;
    public static final boolean VERIFICATION_ENABLED = true;

    public static final int ANONYMOUS_CHAT_CREATION_COST = 100;
    public static final int SEARCH_QUEUE_PAID_THRESHOLD = 10;
    public static final int MESSAGES_COST = 0;

    public static final int MAX_CHATS_PER_USER = 10;
    public static final int MAX_MESSAGES_PER_DAY = 1000;
    public static final int MAX_MESSAGE_LENGTH = 1000;
    public static final int MAX_BIO_LENGTH = 500;

    public static final int INITIAL_USER_BALANCE = 0;

    public static final int VK_PAY_COIN_RATE = 100; // 1 рубль = 100 фиан
    public static final int VOTES_COIN_RATE = 10;   // 1 голос = 10 фиан

    public static final int TYPING_CLEANUP_INTERVAL = 10000; // 10 seconds

    public static final String[] ALLOWED_ORIGINS = System.getenv("CORS_ALLOWED_ORIGINS").split(",");
    public static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT", "DELETE", "OPTIONS"};
    public static final String[] ALLOWED_HEADERS = {"Content-Type", "Authorization"};

    public static final String UPLOAD_DIR = "uploads";
    public static final long MAX_UPLOAD_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    public static final String APP_VERSION = "1.0.0";
    public static final String APP_NAME = "TindApp";

    public static final long VK_COMMUNITY_GROUP_ID = Long.parseLong(System.getenv("VK_COMMUNITY_GROUP_ID"));
    public static final String VK_COMMUNITY_ACCESS_TOKEN = System.getenv("VK_COMMUNITY_ACCESS_TOKEN");
    public static final String VK_API_VERSION = "5.199";
    public static final int VK_APP_ID = Integer.parseInt(System.getenv("VK_APP_ID"));

    public static io.vertx.core.json.JsonObject getHttpConfig() {
        return new io.vertx.core.json.JsonObject()
            .put("port", HTTP_PORT)
            .put("host", HTTP_HOST);
    }

    public static io.vertx.core.json.JsonObject getVkConfig() {
        return new io.vertx.core.json.JsonObject()
            .put("client", new io.vertx.core.json.JsonObject()
                .put("secret", VK_CLIENT_SECRET));
    }

    public static io.vertx.core.json.JsonObject getFeaturesConfig() {
        return new io.vertx.core.json.JsonObject()
            .put("anonymousChat", ANONYMOUS_CHAT_ENABLED)
            .put("profiles", PROFILES_ENABLED)
            .put("subscriptions", SUBSCRIPTIONS_ENABLED)
            .put("verification", VERIFICATION_ENABLED);
    }

    public static io.vertx.core.json.JsonObject getCostsConfig() {
        return new io.vertx.core.json.JsonObject()
            .put("anonymousChatCreation", ANONYMOUS_CHAT_CREATION_COST)
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
