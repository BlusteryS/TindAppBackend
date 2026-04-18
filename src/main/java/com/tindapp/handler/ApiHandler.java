package com.tindapp.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tindapp.config.AppConfig;
import com.tindapp.model.BlackListItem;
import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.model.Notification;
import com.tindapp.model.Report;
import com.tindapp.model.Subscription;
import com.tindapp.model.User;
import com.tindapp.service.BlackListService;
import com.tindapp.service.ChatService;
import com.tindapp.service.LocationService;
import com.tindapp.service.MessageService;
import com.tindapp.service.NotificationService;
import com.tindapp.service.ProfileService;
import com.tindapp.service.ReportService;
import com.tindapp.service.SubscriptionService;
import com.tindapp.service.UserService;
import com.tindapp.util.ResponseMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class ApiHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiHandler.class);
    private final ObjectMapper objectMapper;

    public enum ErrorCodes {
        UNAUTHORIZED("UNAUTHORIZED"),
        FORBIDDEN("FORBIDDEN"),
        NOT_FOUND("NOT_FOUND"),
        VALIDATION_ERROR("VALIDATION_ERROR"),
        INSUFFICIENT_BALANCE("INSUFFICIENT_BALANCE"),
        RATE_LIMIT_EXCEEDED("RATE_LIMIT_EXCEEDED"),
        CHAT_NOT_FOUND("CHAT_NOT_FOUND"),
        USER_BLOCKED("USER_BLOCKED"),
        SUBSCRIPTION_REQUIRED("SUBSCRIPTION_REQUIRED"),
        SERVER_ERROR("SERVER_ERROR");

        private final String code;

        ErrorCodes(final String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final UserService userService;
    private final ChatService chatService;
    private final MessageService messageService;
    private final NotificationService notificationService;
    private final SubscriptionService subscriptionService;
    private final ReportService reportService;
    private final BlackListService blackListService;
    private final WebSocketHandler webSocketHandler;
    private final LocationService locationService;
    private final ProfileService profileService;

    public ApiHandler(final UserService userService, final ChatService chatService, final MessageService messageService,
                      final NotificationService notificationService, final SubscriptionService subscriptionService,
                      final ReportService reportService, final BlackListService blackListService,
                      final WebSocketHandler webSocketHandler, final LocationService locationService,
                      final ProfileService profileService) {
        this.userService = userService;
        this.chatService = chatService;
        this.messageService = messageService;
        this.notificationService = notificationService;
        this.subscriptionService = subscriptionService;
        this.reportService = reportService;
        this.blackListService = blackListService;
        this.webSocketHandler = webSocketHandler;
        this.locationService = locationService;
        this.profileService = profileService;

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void getCountries(final RoutingContext ctx) {
        try {
            final List<LocationService.Country> countries = locationService.getCountries();
            final JsonArray countriesArray = new JsonArray(
                countries.stream()
                    .map(country -> new JsonObject()
                        .put("id", country.id())
                        .put("name", country.name()))
                    .collect(Collectors.toList())
            );
            final JsonObject payload = new JsonObject().put("countries", countriesArray);
            sendSuccess(ctx, payload);
        } catch (final Exception e) {
            logger.error("Error fetching countries", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getCitiesByCountry(final RoutingContext ctx) {
        try {
            final String countryId = ctx.pathParam("countryId");

            if (countryId == null || countryId.isEmpty()) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "countryId is required");
                return;
            }

            final String query = ctx.request().getParam("query");
            if (query == null) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "query parameter is required");
                return;
            }

            final String trimmedQuery = query.trim();
            if (trimmedQuery.length() < 3) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "query parameter must be at least 3 characters");
                return;
            }

            final List<LocationService.City> cities = locationService.searchCitiesByCountry(countryId, trimmedQuery);
            final JsonArray citiesArray = new JsonArray(
                cities.stream()
                    .map(city -> new JsonObject()
                        .put("id", city.getId())
                        .put("name", city.getName()))
                    .collect(Collectors.toList())
            );
            final JsonObject payload = new JsonObject()
                .put("countryId", countryId)
                .put("query", trimmedQuery)
                .put("cities", citiesArray);
            sendSuccess(ctx, payload);
        } catch (final Exception e) {
            logger.error("Error fetching cities for country", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getProfiles(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 12);

            final User viewer = userService.getUserById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

            final ProfileService.ProfileFilters filters = profileService.parseFilters(ctx.request().params(), viewer);
            final ProfileService.ProfileSearchResult result = profileService.searchProfiles(viewer, filters, page, limit);

            sendPaginatedSuccess(ctx, result.profiles(), page, limit, result.total());
        } catch (final NumberFormatException e) {
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Invalid pagination parameters");
        } catch (final RuntimeException e) {
            if (e.getMessage().contains("User not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, e.getMessage());
            } else {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, e.getMessage());
            }
        } catch (final Exception e) {
            logger.error("Error getting profiles", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getCurrentUser(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Optional<User> user = userService.getUserById(userId);

            if (user.isPresent()) {
                final JsonObject payload = ResponseMapper.toUserResponse(user.get());
                payload.put("isAnonymousForViewer", false);
                sendSuccess(ctx, payload.getMap());
            } else {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            }
        } catch (final Exception e) {
            logger.error("Error getting current user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getUser(final RoutingContext ctx) {
        try {
            final Long userId = Long.valueOf(ctx.pathParam("userId"));
            final Long viewerId = getUserIdFromContext(ctx);
            final Optional<User> user = userService.getUserById(userId);

            if (user.isPresent()) {
                final User target = user.get();
                final JsonObject payload = ResponseMapper.toUserResponse(target);
                final boolean isAnonymous = shouldMaskUser(viewerId, target);
                if (isAnonymous) {
                    payload
                        .put("firstName", "")
                        .put("lastName", "")
                        .put("avatarUrl", "")
                        .put("isAnonymousForViewer", true);
                } else {
                    payload.put("isAnonymousForViewer", false);
                }
                sendSuccess(ctx, payload.getMap());
            } else {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            }
        } catch (final Exception e) {
            logger.error("Error getting user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void updateProfile(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final String firstName = trimToNull(body.getString("firstName"));
            final String lastName = trimToNull(body.getString("lastName"));
            final String avatarUrl = trimToNull(body.getString("avatarUrl"));
            final String gender = normalizeGender(body.getString("gender"));
            final String bio = trimToNull(body.getString("bio"));
            final String country = trimToNull(body.getString("country"));
            final String city = trimToNull(body.getString("city"));
            final Integer age = body.getInteger("age");
            final String birthDate = trimToNull(body.getString("birthDate"));
            final Boolean isVisible = body.getBoolean("isVisible");
            final Integer profileCost = body.getInteger("profileCost");
            final String nativeLanguage = trimToNull(body.getString("nativeLanguage"));

            User.UserSettings settings = null;
            final JsonObject settingsJson = body.getJsonObject("settings");
            if (settingsJson != null) {
                settings = new User.UserSettings(false);
                if (settingsJson.containsKey("showAge")) {
                    settings.setShowAge(settingsJson.getBoolean("showAge"));
                }
                if (settingsJson.containsKey("showCity")) {
                    settings.setShowCity(settingsJson.getBoolean("showCity"));
                }
                if (settingsJson.containsKey("allowMessages")) {
                    settings.setAllowMessages(settingsJson.getBoolean("allowMessages"));
                }
                if (settingsJson.containsKey("allowCommunityMessages")) {
                    settings.setAllowCommunityMessages(settingsJson.getBoolean("allowCommunityMessages"));
                }
                if (settingsJson.containsKey("notifyAnonMessages")) {
                    settings.setNotifyAnonMessages(settingsJson.getBoolean("notifyAnonMessages"));
                }
                if (settingsJson.containsKey("notifyAnonDialogClosed")) {
                    settings.setNotifyAnonDialogClosed(settingsJson.getBoolean("notifyAnonDialogClosed"));
                }
                if (settingsJson.containsKey("notifyProfileNewChat")) {
                    settings.setNotifyProfileNewChat(settingsJson.getBoolean("notifyProfileNewChat"));
                }
                if (settingsJson.containsKey("notifyProfileMessages")) {
                    settings.setNotifyProfileMessages(settingsJson.getBoolean("notifyProfileMessages"));
                }
                if (settingsJson.containsKey("notifyProfileDialogClosed")) {
                    settings.setNotifyProfileDialogClosed(settingsJson.getBoolean("notifyProfileDialogClosed"));
                }
                if (settingsJson.containsKey("notifySubscriptionProblems")) {
                    settings.setNotifySubscriptionProblems(settingsJson.getBoolean("notifySubscriptionProblems"));
                }
            }

            final User updatedUser = userService.updateProfile(
                userId,
                firstName,
                lastName,
                avatarUrl,
                gender,
                bio,
                country,
                city,
                age,
                birthDate,
                isVisible,
                settings,
                profileCost,
                nativeLanguage
            );
            sendSuccess(ctx, ResponseMapper.toUserResponse(updatedUser).getMap());
            webSocketHandler.notifyProfileUpdated(updatedUser);
        } catch (final Exception e) {
            logger.error("Error updating profile", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, e.getMessage());
        }
    }

    public void verifyUser(final RoutingContext ctx) {
        FileUpload upload = null;
        Path selfiePath = null;
        try {
            final Long userId = getUserIdFromContext(ctx);
            if (userId == null) {
                sendError(ctx, 401, ErrorCodes.UNAUTHORIZED, "Not authenticated");
                return;
            }

            final List<FileUpload> uploads = ctx.fileUploads();
            if (uploads == null || uploads.isEmpty()) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Selfie is required");
                return;
            }

            upload = uploads.iterator().next();

            if (upload.size() > AppConfig.MAX_UPLOAD_SIZE_BYTES) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "File too large");
                deleteTempFile(upload);
                return;
            }

            final String contentType = upload.contentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Only image uploads are allowed");
                deleteTempFile(upload);
                return;
            }

            final String originalFileName = upload.fileName();
            String extension = getFileExtension(originalFileName);
            if (extension.isEmpty()) {
                extension = getExtensionFromContentType(contentType);
            }
            if (extension.isEmpty()) {
                extension = ".jpg";
            }

            final String fileName = "selfie-" + UUID.randomUUID() + extension;
            selfiePath = Paths.get(AppConfig.UPLOAD_DIR, fileName);

            Files.move(Paths.get(upload.uploadedFileName()), selfiePath, StandardCopyOption.REPLACE_EXISTING);

            final UserService.UserVerificationResult result = userService.verifyUserWithSelfie(userId, selfiePath);

            final JsonObject response = new JsonObject()
                .put("isVerified", result.verified())
                .put("similarity", result.similarity())
                .put("reason", result.reason());
            sendSuccess(ctx, response);
        } catch (final IllegalArgumentException e) {
            logger.warn("Verification validation error", e);
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, e.getMessage());
        } catch (final RuntimeException e) {
            logger.error("Error verifying user", e);
            final String message = e.getMessage() != null ? e.getMessage() : "Verification failed";
            final String lower = message.toLowerCase();
            if (lower.contains("subscription required")) {
                sendError(ctx, 403, ErrorCodes.SUBSCRIPTION_REQUIRED, message);
            } else if (lower.contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, message);
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, message);
            }
        } catch (final Exception e) {
            logger.error("Unexpected error verifying user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        } finally {
            if (selfiePath != null) {
                try {
                    Files.deleteIfExists(selfiePath);
                } catch (final Exception cleanupError) {
                    logger.warn("Failed to cleanup selfie file {}", selfiePath, cleanupError);
                }
            }
            if (upload != null) {
                deleteTempFile(upload);
            }
        }
    }

    private String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeGender(final String gender) {
        if (gender == null) {
            return null;
        }
        final String normalized = gender.trim().toLowerCase();
        switch (normalized) {
            case "male":
            case "female":
            case "other":
                return normalized;
            default:
                return null;
        }
    }

    private JsonObject toRewardsJson(final UserService.RewardStatus status) {
        if (status == null) {
            return new JsonObject();
        }
        final JsonObject ad = new JsonObject()
            .put("available", status.adAvailable());
        if (status.adCooldownSeconds() != null) {
            ad.put("cooldownSeconds", status.adCooldownSeconds());
        }

        final JsonObject subscription = new JsonObject()
            .put("available", status.subscriptionAvailable())
            .put("claimed", status.subscriptionClaimed());

        return new JsonObject()
            .put("ad", ad.getMap())
            .put("subscription", subscription.getMap());
    }

    private UserService.RewardType parseRewardType(final String type) {
        if (type == null) {
            throw new RuntimeException("Reward type is required");
        }
        switch (type.toLowerCase()) {
            case "ad":
                return UserService.RewardType.AD;
            case "subscription":
            case "community":
                return UserService.RewardType.COMMUNITY;
            default:
                throw new RuntimeException("Unknown reward type");
        }
    }

    public void getBalance(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Integer balance = userService.getUserBalance(userId);

            final JsonObject response = new JsonObject().put("balance", balance);
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error getting user balance", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getRewards(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final UserService.RewardStatus status = userService.getRewardStatus(userId);
            sendSuccess(ctx, toRewardsJson(status));
        } catch (final Exception e) {
            logger.error("Error getting rewards status", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void purchaseCoins(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final Integer amount = body.getInteger("amount");

            final User updatedUser = userService.purchaseCoins(userId, amount);

            final JsonObject response = new JsonObject().put("balance", updatedUser.getBalance());
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error purchasing coins", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void claimReward(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();
            if (body == null) {
                body = new JsonObject();
            }

            final String type = trimToNull(body.getString("type"));
            final boolean success = body.getBoolean("success", false);
            final UserService.RewardType rewardType = parseRewardType(type);

            final UserService.RewardClaimResult result = userService.claimReward(userId, rewardType, success);
            final JsonObject response = new JsonObject()
                .put("balance", result.balance())
                .put("rewarded", result.rewardedAmount())
                .put("rewards", toRewardsJson(result.rewards()));
            sendSuccess(ctx, response);
        } catch (final RuntimeException e) {
            logger.error("Error claiming reward", e);
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, e.getMessage());
        } catch (final Exception e) {
            logger.error("Unexpected error claiming reward", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getUserStats(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final UserService.UserStats stats = userService.getUserStats(userId);
            sendSuccess(ctx, stats);
        } catch (final Exception e) {
            logger.error("Error getting user stats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChatCost(final RoutingContext ctx) {
        try {
            final int cost = chatService.getChatCost();
            final JsonObject response = new JsonObject().put("cost", cost);
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error getting chat cost", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void startProfileChat(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Long profileId = Long.valueOf(ctx.pathParam("profileId"));

            final Chat chat = chatService.startProfileChat(userId, profileId);
            final JsonObject response = new JsonObject()
                .put("chat", ResponseMapper.toChatResponse(chat).getMap())
                .put("cost", chat.getSettings() != null ? chat.getSettings().getCost() : 0);

            userService.getUserById(userId).ifPresent(user -> response.put("balance", user.getBalance()));

            sendSuccess(ctx, response);
        } catch (final NumberFormatException e) {
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Invalid profileId");
        } catch (final RuntimeException e) {
            final String message = e.getMessage() != null ? e.getMessage() : "Unable to start chat";
            final String lower = message.toLowerCase();
            if (lower.contains("insufficient")) {
                sendError(ctx, 402, ErrorCodes.INSUFFICIENT_BALANCE, message);
            } else if (lower.contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, message);
            } else {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, message);
            }
        } catch (final Exception e) {
            logger.error("Error starting profile chat", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChats(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 20);

            final List<Chat> chats = chatService.getUserChats(userId, page, limit);
            final int total = chatService.countUserChats(userId);
            final List<Map<String, Object>> chatResponses = chats.stream()
                .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                .collect(Collectors.toList());

            sendPaginatedSuccess(ctx, chatResponses, page, limit, total);
        } catch (final Exception e) {
            logger.error("Error getting chats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChat(final RoutingContext ctx) {
        try {
            final String chatId = ctx.pathParam("chatId");
            final Long userId = getUserIdFromContext(ctx);

            logger.info("Getting chat: chatId={}, userId={}", chatId, userId);

            if (!chatService.isUserInChat(chatId, userId)) {
                logger.warn("User {} denied access to chat {}", userId, chatId);
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            final Optional<Chat> chat = chatService.getChatById(chatId);
            if (chat.isPresent()) {
                logger.info("Chat found and returned: chatId={}", chatId);
                sendSuccess(ctx, ResponseMapper.toChatResponse(chat.get()).getMap());
            } else {
                logger.warn("Chat not found: chatId={}", chatId);
                sendError(ctx, 404, ErrorCodes.CHAT_NOT_FOUND, "Chat not found");
            }
        } catch (final Exception e) {
            logger.error("Error getting chat: chatId={}", ctx.pathParam("chatId"), e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getSearchStatus(final RoutingContext ctx) {
        logger.info("=== getSearchStatus method called ===");
        logger.info("Request path: {}, method: {}", ctx.request().path(), ctx.request().method());
        logger.info("Headers: Authorization={}", ctx.request().getHeader("Authorization"));
        try {

            final Long userId = getUserIdFromContext(ctx);

            final boolean isSearching = chatService.isSearchingCompanion(userId);
            final int queueSize = chatService.getSearchQueueSize();

            final JsonObject response = new JsonObject()
                .put("isSearching", isSearching)
                .put("queueSize", queueSize);

            sendSuccess(ctx, response);
        } catch (final RuntimeException e) {
            if (e.getMessage().contains("User ID not found in context")) {
                logger.error("Authentication error in getSearchStatus: context has currentUser={}, userId={}",
                    ctx.get("currentUser"), ctx.get("userId"), e);
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
            } else {
                logger.error("Runtime error in getSearchStatus", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        } catch (final Exception e) {
            logger.error("Error getting search status", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void endChat(final RoutingContext ctx) {
        try {
            final String chatId = ctx.pathParam("chatId");
            final Long userId = getUserIdFromContext(ctx);

            final Chat closedChat = chatService.endChat(chatId, userId);
            if (closedChat != null) {
                webSocketHandler.notifyChatClosed(
                    closedChat.getId(),
                    closedChat.getClosedByUserId(),
                    closedChat.getClosureReason(),
                    closedChat.getClosedAt()
                );
            }

            final JsonObject response = new JsonObject()
                .put("success", true)
                .put("chat", ResponseMapper.toChatResponse(closedChat).getMap());
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error ending chat", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getMessages(final RoutingContext ctx) {
        try {
            final String chatId = ctx.pathParam("chatId");
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 50);

            if (!chatService.isUserInChat(chatId, userId)) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            final List<Message> messages = messageService.getChatMessages(chatId, page, limit);
            final int total = (int) messageService.countMessages(chatId);
            final List<Map<String, Object>> messageResponses = messages.stream()
                .map(message -> ResponseMapper.toMessageResponse(message).getMap())
                .collect(Collectors.toList());

            sendPaginatedSuccess(ctx, messageResponses, page, limit, total);
        } catch (final Exception e) {
            logger.error("Error getting messages", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void uploadImage(final RoutingContext ctx) {
        FileUpload upload = null;
        try {
            final Long userId = getUserIdFromContext(ctx);
            if (userId == null) {
                sendError(ctx, 401, ErrorCodes.UNAUTHORIZED, "Not authenticated");
                return;
            }

            final List<FileUpload> uploads = ctx.fileUploads();
            if (uploads == null || uploads.isEmpty()) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "No files uploaded");
                return;
            }

            upload = uploads.iterator().next();

            if (upload.size() > AppConfig.MAX_UPLOAD_SIZE_BYTES) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "File too large");
                deleteTempFile(upload);
                return;
            }

            final String contentType = upload.contentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Only image uploads are allowed");
                deleteTempFile(upload);
                return;
            }

            final String originalFileName = upload.fileName();
            String extension = getFileExtension(originalFileName);
            if (extension.isEmpty()) {
                extension = getExtensionFromContentType(contentType);
            }

            if (extension.isEmpty()) {
                extension = ".jpg";
            }

            final String fileName = UUID.randomUUID() + extension;
            final Path targetPath = Paths.get(AppConfig.UPLOAD_DIR, fileName);

            Files.move(Paths.get(upload.uploadedFileName()), targetPath, StandardCopyOption.REPLACE_EXISTING);

            final JsonObject data = new JsonObject()
                .put("url", "/uploads/" + fileName)
                .put("preview", "/uploads/" + fileName);

            sendSuccess(ctx, data);
        } catch (final Exception e) {
            logger.error("Error uploading image", e);
            if (upload != null) {
                deleteTempFile(upload);
            }
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Failed to upload image");
        }
    }

    public void sendMessage(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final String chatId = body.getString("chatId");
            final String text = body.getString("text", "");
            final String replyToMessageId = body.getString("replyToMessageId");
            final JsonArray attachmentsJson = body.getJsonArray("attachments");
            final List<Message.MessageAttachment> attachments = parseAttachments(attachmentsJson);

            final Message message = messageService.sendMessage(userId, chatId, text, replyToMessageId, attachments);
            final JsonObject messageJson = ResponseMapper.toMessageResponse(message);
            final String senderName = resolveUserDisplayName(userId);

            logger.info("Broadcasting message via WebSocket: chatId={}, messageId={}", chatId, message.getId());
            webSocketHandler.sendMessageToUser(userId, "message", messageJson.copy());

            final Optional<Chat> chatOpt = chatService.getChatById(chatId);
            if (chatOpt.isPresent()) {
                final Chat chat = chatOpt.get();
                final Long companionId = chat.getCompanionId(userId);
                if (companionId != null) {
                    webSocketHandler.sendMessageToUser(companionId, "message", messageJson.copy());
                    final String senderDisplayName =
                        chat.getType() == Chat.ChatType.ANONYMOUS
                            ? "Собеседник"
                            : senderName;
                    notificationService.sendNewMessageNotification(companionId, chat.getType(), senderDisplayName);
                }
            }

            sendSuccess(ctx, messageJson.getMap());
        } catch (final Exception e) {
            logger.error("Error sending message", e);
            if (e.getMessage().contains("Insufficient balance")) {
                sendError(ctx, 400, ErrorCodes.INSUFFICIENT_BALANCE, "Insufficient balance");
            } else if (e.getMessage().contains("blocked")) {
                sendError(ctx, 403, ErrorCodes.USER_BLOCKED, "User is blocked");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void editMessage(final RoutingContext ctx) {
        try {
            final String messageId = ctx.pathParam("messageId");
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final String text = body.getString("text");
            final Message editedMessage = messageService.editMessage(messageId, userId, text);
            sendSuccess(ctx, ResponseMapper.toMessageResponse(editedMessage).getMap());
        } catch (final Exception e) {
            logger.error("Error editing message", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Message not found");
            } else if (e.getMessage().contains("permission")) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "No permission to edit message");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void deleteMessage(final RoutingContext ctx) {
        try {
            final String messageId = ctx.pathParam("messageId");
            final Long userId = getUserIdFromContext(ctx);

            messageService.deleteMessage(messageId, userId);

            final JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error deleting message", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Message not found");
            } else if (e.getMessage().contains("permission")) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "No permission to delete message");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void createReport(final RoutingContext ctx) {
        try {
            final Long reporterId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final Long targetId = body.getLong("targetId");
            final String chatId = body.getString("chatId");
            final String messageId = body.getString("messageId");
            final String reasonStr = body.getString("reason");
            final String description = body.getString("description");

            Report.ReportReason reason = Report.ReportReason.OTHER;
            if (reasonStr != null && !reasonStr.trim().isEmpty()) {
                try {
                    reason = Report.ReportReason.valueOf(reasonStr.trim().toUpperCase());
                } catch (final Exception ignored) {
                    reason = Report.ReportReason.OTHER;
                }
            }

            final Report report = reportService.createReport(reporterId, targetId, chatId, messageId, reason, description);
            sendSuccess(ctx, ResponseMapper.toReportResponse(report).getMap());
        } catch (final Exception e) {
            logger.error("Error creating report", e);
            final String message = e.getMessage() != null ? e.getMessage() : "";
            if (message.contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User or resource not found");
            } else if (message.contains("Report already exists")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Вы уже отправили жалобу");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getReports(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 20);

            final User currentUser = userService.getUserById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

            final List<Report> reports;
            final int total;
            if (currentUser.isAdmin()) {
                reports = reportService.getAllReports(page, limit);
                total = (int) reportService.countReports();
            } else {
                reports = reportService.getUserReports(userId, page, limit);
                total = (int) reportService.countUserReports(userId);
            }

            final List<Map<String, Object>> payload = new ArrayList<>();
            for (final Report report : reports) {
                final JsonObject reportJson = ResponseMapper.toReportResponse(report);
                if (report.getChatId() != null) {
                    final List<Message> lastMessages = messageService.getRecentMessages(report.getChatId(), 5);
                    final List<Map<String, Object>> mappedMessages = lastMessages.stream()
                        .map(message -> ResponseMapper.toMessageResponse(message).getMap())
                        .collect(Collectors.toList());
                    reportJson.put("lastMessages", mappedMessages);
                }
                payload.add(reportJson.getMap());
            }

            sendPaginatedSuccess(ctx, payload, page, limit, total);
        } catch (final Exception e) {
            logger.error("Error getting reports", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void updateReportStatus(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final User currentUser = userService.getUserById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

            if (!currentUser.isAdmin()) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            final String reportId = ctx.pathParam("reportId");
            final JsonObject body = ctx.getBodyAsJson();
            if (body == null || !body.containsKey("status")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Status is required");
                return;
            }

            final String statusValue = body.getString("status");
            final Report.ReportStatus status;
            try {
                status = Report.ReportStatus.valueOf(statusValue.toUpperCase());
            } catch (final IllegalArgumentException e) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Invalid status value");
                return;
            }

            reportService.updateReportStatus(reportId, status);
            final Report updatedReport = reportService.getReportById(reportId)
                .orElseThrow(() -> new RuntimeException("Report not found"));

            final JsonObject payload = ResponseMapper.toReportResponse(updatedReport);
            sendSuccess(ctx, payload.getMap());
        } catch (final Exception e) {
            logger.error("Error updating report status", e);
            if (e.getMessage() != null && e.getMessage().contains("Report not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Report not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void blockUser(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final Long blockedUserId = body.getLong("userId");
            final String reason = body.getString("reason");

            final BlackListItem blackListItem = blackListService.blockUser(userId, blockedUserId, reason);
            final List<Chat> closedChats = chatService.closeChatsBetween(
                userId,
                blockedUserId,
                Chat.ChatClosureReason.BLOCKED
            );

            for (final Chat closedChat : closedChats) {
                webSocketHandler.notifyChatClosed(
                    closedChat.getId(),
                    closedChat.getClosedByUserId(),
                    closedChat.getClosureReason(),
                    closedChat.getClosedAt()
                );
            }

            final JsonObject response = ResponseMapper.toBlackListItemResponse(blackListItem);
            response.put(
                "closedChats",
                closedChats.stream()
                    .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                    .collect(Collectors.toList())
            );
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error blocking user", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            } else if (e.getMessage().contains("already blocked")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "User already blocked");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void unblockUser(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Long blockedUserId = Long.valueOf(ctx.pathParam("userId"));

            blackListService.unblockUser(userId, blockedUserId);

            final List<Chat> reopenedChats = chatService.reopenChatsBetween(userId, blockedUserId);
            for (final Chat reopened : reopenedChats) {
                webSocketHandler.notifyChatReopened(reopened.getId());
            }

            final JsonObject response = new JsonObject()
                .put("success", true)
                .put(
                    "reopenedChats",
                    reopenedChats.stream()
                        .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                        .collect(Collectors.toList())
                );
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error unblocking user", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found in blacklist");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getBlacklist(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 20);

            final List<BlackListItem> blackList = blackListService.getUserBlackList(userId, page, limit);
            final int total = (int) blackListService.getBlockedUsersCount(userId);
            final List<Map<String, Object>> payload = blackList.stream()
                .map(item -> ResponseMapper.toBlackListItemResponse(item).getMap())
                .collect(Collectors.toList());
            sendPaginatedSuccess(ctx, payload, page, limit, total);
        } catch (final Exception e) {
            logger.error("Error getting blacklist", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getBlacklistStatus(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Long blockedUserId = Long.valueOf(ctx.pathParam("userId"));
            final boolean blocked = blackListService.isUserBlocked(userId, blockedUserId);

            sendSuccess(ctx, new JsonObject().put("blocked", blocked));
        } catch (final Exception e) {
            logger.error("Error getting blacklist status", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void banUser(final RoutingContext ctx) {
        try {
            final Long adminId = getUserIdFromContext(ctx);
            final User admin = userService.getUserById(adminId)
                .orElseThrow(() -> new RuntimeException("User not found"));
            if (!admin.isAdmin()) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            final Long targetId = Long.valueOf(ctx.pathParam("userId"));
            final JsonObject body = ctx.getBodyAsJson();
            final String reason = body != null ? body.getString("reason") : null;

            final User bannedUser = userService.banUser(targetId, reason);
            final List<Chat> closedChats = chatService.closeAllChatsForUser(targetId, Chat.ChatClosureReason.SYSTEM);
            for (final Chat chat : closedChats) {
                webSocketHandler.notifyChatClosed(
                    chat.getId(),
                    chat.getClosedByUserId(),
                    chat.getClosureReason(),
                    chat.getClosedAt()
                );
            }

            sendSuccess(ctx, ResponseMapper.toUserResponse(bannedUser).getMap());
        } catch (final Exception e) {
            logger.error("Error banning user", e);
            if (e.getMessage() != null && e.getMessage().contains("User not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void unbanUser(final RoutingContext ctx) {
        try {
            final Long adminId = getUserIdFromContext(ctx);
            final User admin = userService.getUserById(adminId)
                .orElseThrow(() -> new RuntimeException("User not found"));
            if (!admin.isAdmin()) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            final Long targetId = Long.valueOf(ctx.pathParam("userId"));
            final User unbannedUser = userService.unbanUser(targetId);
            sendSuccess(ctx, ResponseMapper.toUserResponse(unbannedUser).getMap());
        } catch (final Exception e) {
            logger.error("Error unbanning user", e);
            if (e.getMessage() != null && e.getMessage().contains("User not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getSubscriptionPlans(final RoutingContext ctx) {
        try {
            final List<SubscriptionService.SubscriptionPlan> plans = subscriptionService.getAvailablePlans();
            sendSuccess(ctx, plans);
        } catch (final Exception e) {
            logger.error("Error getting subscription plans", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getActiveSubscription(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Optional<Subscription> subscription = subscriptionService.getActiveSubscription(userId);

            if (subscription.isPresent()) {
                sendSuccess(ctx, subscription.get());
            } else {
                sendSuccess(ctx, null);
            }
        } catch (final Exception e) {
            logger.error("Error getting active subscription", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void purchaseSubscription(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final String planId = body.getString("planId");
            final String paymentMethodStr = body.getString("paymentMethod");
            final Subscription.PaymentMethod paymentMethod = Subscription.PaymentMethod.valueOf(paymentMethodStr.toUpperCase());

            final Subscription subscription = subscriptionService.purchaseSubscription(userId, planId, paymentMethod);
            sendSuccess(ctx, subscription);
        } catch (final Exception e) {
            logger.error("Error purchasing subscription", e);
            if (e.getMessage().contains("Insufficient balance")) {
                sendError(ctx, 400, ErrorCodes.INSUFFICIENT_BALANCE, "Insufficient balance");
            } else if (e.getMessage().contains("already active")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Subscription already active");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void cancelSubscription(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            subscriptionService.cancelSubscription(userId);

            final JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error cancelling subscription", e);
            if (e.getMessage().contains("no active subscription")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "No active subscription to cancel");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getNotifications(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 20);

            final List<Notification> notifications = notificationService.getUserNotifications(userId, page, limit);
            final int total = notificationService.countUserNotifications(userId);
            sendPaginatedSuccess(ctx, notifications, page, limit, total);
        } catch (final Exception e) {
            logger.error("Error getting notifications", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void markNotificationsAsRead(final RoutingContext ctx) {
        try {
            final JsonObject body = ctx.getBodyAsJson();
            final List<String> notificationIds = body.getJsonArray("notificationIds")
                .stream()
                .map(Object::toString)
                .collect(Collectors.toList());

            notificationService.markNotificationsAsRead(notificationIds);

            final JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error marking notifications as read", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void deleteNotification(final RoutingContext ctx) {
        try {
            final String notificationId = ctx.pathParam("notificationId");
            final Long userId = getUserIdFromContext(ctx);

            notificationService.deleteNotification(notificationId, userId);

            final JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error deleting notification", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Notification not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void updateCommunityNotifications(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();
            final boolean enabled = body == null || body.getBoolean("enabled", true);

            userService.updateCommunityNotifications(userId, enabled);
            boolean testSent = false;
            UserService.RewardClaimResult rewardResult = null;
            if (enabled) {
                testSent = notificationService.sendCommunityTestNotification(userId);
                try {
                    rewardResult = userService.claimReward(userId, UserService.RewardType.COMMUNITY, true);
                } catch (final RuntimeException rewardException) {
                    logger.info("Community reward not issued for user {}: {}", userId, rewardException.getMessage());
                } catch (final Exception rewardException) {
                    logger.warn("Failed to issue community reward for user {}", userId, rewardException);
                }
            }

            final JsonObject response = new JsonObject()
                .put("enabled", enabled)
                .put("testSent", testSent);
            if (rewardResult != null) {
                response
                    .put("rewarded", rewardResult.rewardedAmount())
                    .put("balance", rewardResult.balance())
                    .put("rewards", toRewardsJson(rewardResult.rewards()));
            }
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error updating community notifications", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Failed to update community notifications");
        }
    }

    public void getOnlineStats(final RoutingContext ctx) {
        try {
            final UserService.OnlineStats stats = userService.getOnlineStats();
            final JsonObject response = new JsonObject()
                .put("anonymousChats", chatService.getActiveAnonymousChatsCount())
                .put("totalUsers", stats.totalUsers())
                .put("activeUsers", stats.activeUsers());
            sendSuccess(ctx, response);
        } catch (final Exception e) {
            logger.error("Error getting online stats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getAppConfig(final RoutingContext ctx) {
        try {
            final JsonObject config = AppConfig.getClientConfig();
            sendSuccess(ctx, config);
        } catch (final Exception e) {
            logger.error("Error getting app config", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    private Long getUserIdFromContext(final RoutingContext ctx) {
        final Long userId = ctx.get("userId");
        if (userId == null) {
            throw new RuntimeException("User ID not found in context");
        }
        return userId;
    }

    private int parseIntParam(final String value, final int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            throw e;
        }
    }

    private void deleteTempFile(final FileUpload upload) {
        try {
            Files.deleteIfExists(Paths.get(upload.uploadedFileName()));
        } catch (final Exception ignored) {
        }
    }

    private String getFileExtension(final String fileName) {
        if (fileName == null) {
            return "";
        }
        final int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase();
    }

    private String getExtensionFromContentType(final String contentType) {
        if (contentType == null) {
            return "";
        }
        switch (contentType) {
            case "image/jpeg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            default:
                return "";
        }
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
            final String typeString = attachmentJson.getString("type", "image");
            final Message.MessageAttachment.AttachmentType type = parseAttachmentType(typeString);
            final String url = attachmentJson.getString("url");
            if (url == null || url.isEmpty()) {
                continue;
            }
            final String preview = attachmentJson.getString("preview", url);
            final Message.MessageAttachment attachment = new Message.MessageAttachment(type, url, preview);
            attachments.add(attachment);
        }
        return attachments;
    }

    private Message.MessageAttachment.AttachmentType parseAttachmentType(final String typeString) {
        if (typeString == null) {
            return Message.MessageAttachment.AttachmentType.IMAGE;
        }
        switch (typeString.toLowerCase()) {
            case "sticker":
                return Message.MessageAttachment.AttachmentType.STICKER;
            case "image":
            default:
                return Message.MessageAttachment.AttachmentType.IMAGE;
        }
    }

    private String resolveUserDisplayName(final Long userId) {
        if (userId == null) {
            return "Пользователь";
        }
        return userService.getUserById(userId)
            .map(user -> {
                final String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
                final String last = user.getLastName() != null ? user.getLastName().trim() : "";
                final String full = (first + ' ' + last).trim();
                if (!full.isEmpty()) {
                    return full;
                }
                if (!first.isEmpty()) {
                    return first;
                }
                if (!last.isEmpty()) {
                    return last;
                }
                return "Пользователь #" + userId;
            })
            .orElse("Пользователь #" + userId);
    }

    private boolean shouldMaskUser(final Long viewerId, final User target) {
        if (target == null || target.getId() == null) {
            return true;
        }

        if (target.getId().equals(viewerId)) {
            return false;
        }

        if (viewerId != null) {
            final Optional<User> viewer = userService.getUserById(viewerId);
            if (viewer.isPresent() && viewer.get().isAdmin()) {
                return false;
            }
            return !chatService.hasRegularChatBetween(viewerId, target.getId());
        }

        return true;
    }

    private void sendSuccess(final RoutingContext ctx, final Object data) {
        try {
            final JsonObject response;
            if (data != null) {
                final Object parsedData;

                if (data instanceof JsonObject || data instanceof JsonArray) {
                    parsedData = data;
                } else if (data instanceof Iterable) {
                    final JsonArray array = new JsonArray();
                    for (final Object item : (Iterable<?>) data) {
                        array.add(item);
                    }
                    parsedData = array;
                } else if (data.getClass().isArray()) {
                    final JsonArray array = new JsonArray();
                    final int length = java.lang.reflect.Array.getLength(data);
                    for (int i = 0; i < length; i++) {
                        array.add(java.lang.reflect.Array.get(data, i));
                    }
                    parsedData = array;
                } else {
                    final String dataJson = objectMapper.writeValueAsString(data);
                    final String trimmed = dataJson.trim();
                    if (trimmed.startsWith("[")) {
                        parsedData = new JsonArray(trimmed);
                    } else if (trimmed.startsWith("{")) {
                        parsedData = new JsonObject(trimmed);
                    } else {
                        parsedData = data;
                    }
                }

                response = new JsonObject()
                    .put("success", true)
                    .put("data", parsedData);
            } else {
                response = new JsonObject()
                    .put("success", true)
                    .put("data", null);
            }

            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        } catch (final Exception e) {
            logger.error("Error sending success response", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    private void sendPaginatedSuccess(final RoutingContext ctx, final List<?> data, final int page, final int limit, final int total) {
        try {
            final JsonObject pagination = new JsonObject()
                .put("page", page)
                .put("limit", limit)
                .put("total", total)
                .put("totalPages", (int) Math.ceil((double) total / limit));

            final String dataJson = objectMapper.writeValueAsString(data);
            final io.vertx.core.json.JsonArray parsedData = new io.vertx.core.json.JsonArray(dataJson);

            final JsonObject response = new JsonObject()
                .put("success", true)
                .put("data", parsedData)
                .put("pagination", pagination);

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        } catch (final Exception e) {
            logger.error("Error sending paginated response", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Error serializing response");
        }
    }

    private void sendError(final RoutingContext ctx, final int statusCode, final ErrorCodes errorCode, final String message) {
        final JsonObject error = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("code", errorCode.getCode());

        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(error.encode());
    }

}
