package com.tindapp.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tindapp.config.AppConfig;
import com.tindapp.model.Chat;
import com.tindapp.model.Message;
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
import io.vertx.core.Future;
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
            userService.getUserById(userId)
                .compose(userOpt -> {
                    if (userOpt.isEmpty()) {
                        return Future.failedFuture(new RuntimeException("User not found"));
                    }
                    final User viewer = userOpt.get();
                    final ProfileService.ProfileFilters filters = profileService.parseFilters(ctx.request().params(), viewer);
                    return profileService.searchProfiles(viewer, filters, page, limit);
                })
                .onSuccess(result -> sendPaginatedSuccess(ctx, result.profiles(), page, limit, result.total()))
                .onFailure(e -> {
                    if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().contains("User not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, e.getMessage());
                    } else {
                        logger.error("Error getting profiles", e);
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final NumberFormatException e) {
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Invalid pagination parameters");
        }
    }

    public void getCurrentUser(final RoutingContext ctx) {
        final Long userId = getUserIdFromContext(ctx);
        userService.getUserById(userId)
            .onSuccess(user -> {
                if (user.isEmpty()) {
                    sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
                    return;
                }
                final JsonObject payload = ResponseMapper.toUserResponse(user.get());
                payload.put("isAnonymousForViewer", false);
                sendSuccess(ctx, payload.getMap());
            })
            .onFailure(e -> {
                logger.error("Error getting current user", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            });
    }

    public void getUser(final RoutingContext ctx) {
        try {
            final Long userId = Long.valueOf(ctx.pathParam("userId"));
            final Long viewerId = getUserIdFromContext(ctx);
            userService.getUserById(userId)
                .compose(user -> {
                    if (user.isEmpty()) {
                        return Future.failedFuture(new RuntimeException("User not found"));
                    }
                    final User target = user.get();
                    return shouldMaskUser(viewerId, target).map(isAnonymous -> {
                        final JsonObject payload = ResponseMapper.toUserResponse(target);
                        if (isAnonymous) {
                            payload.put("firstName", "").put("lastName", "").put("avatarUrl", "").put("isAnonymousForViewer", true);
                        } else {
                            payload.put("isAnonymousForViewer", false);
                        }
                        return payload;
                    });
                })
                .onSuccess(payload -> sendSuccess(ctx, payload.getMap()))
                .onFailure(e -> {
                    if (e.getMessage() != null && e.getMessage().contains("User not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
                    } else {
                        logger.error("Error getting user", e);
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
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

            userService.updateProfile(
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
            ).onSuccess(updatedUser -> {
                sendSuccess(ctx, ResponseMapper.toUserResponse(updatedUser).getMap());
                webSocketHandler.notifyProfileUpdated(updatedUser);
            }).onFailure(e -> {
                logger.error("Error updating profile", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, e.getMessage());
            });
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

            userService.verifyUserWithSelfie(userId, selfiePath)
                .onSuccess(result -> {
                    final JsonObject response = new JsonObject()
                        .put("isVerified", result.verified())
                        .put("similarity", result.similarity())
                        .put("reason", result.reason());
                    sendSuccess(ctx, response);
                })
                .onFailure(e -> {
                    logger.error("Error verifying user", e);
                    final String message = e.getMessage() != null ? e.getMessage() : "Verification failed";
                    final String lower = message.toLowerCase();
                    if (e instanceof IllegalArgumentException) {
                        sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, message);
                    } else if (lower.contains("subscription required")) {
                        sendError(ctx, 403, ErrorCodes.SUBSCRIPTION_REQUIRED, message);
                    } else if (lower.contains("not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, message);
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, message);
                    }
                });
        } catch (final IllegalArgumentException e) {
            logger.warn("Verification validation error", e);
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, e.getMessage());
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
        final Long userId = getUserIdFromContext(ctx);
        userService.getUserBalance(userId)
            .onSuccess(balance -> sendSuccess(ctx, new JsonObject().put("balance", balance)))
            .onFailure(e -> {
                logger.error("Error getting user balance", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            });
    }

    public void getRewards(final RoutingContext ctx) {
        final Long userId = getUserIdFromContext(ctx);
        userService.getRewardStatus(userId)
            .onSuccess(status -> sendSuccess(ctx, toRewardsJson(status)))
            .onFailure(e -> {
                logger.error("Error getting rewards status", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            });
    }

    public void purchaseCoins(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();
            final Integer amount = body.getInteger("amount");
            userService.purchaseCoins(userId, amount)
                .onSuccess(updatedUser -> sendSuccess(ctx, new JsonObject().put("balance", updatedUser.getBalance())))
                .onFailure(e -> {
                    logger.error("Error purchasing coins", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                });
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

            userService.claimReward(userId, rewardType, success)
                .onSuccess(result -> {
                    final JsonObject response = new JsonObject()
                        .put("balance", result.balance())
                        .put("rewarded", result.rewardedAmount())
                        .put("rewards", toRewardsJson(result.rewards()));
                    sendSuccess(ctx, response);
                })
                .onFailure(e -> {
                    logger.error("Error claiming reward", e);
                    sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, e.getMessage());
                });
        } catch (final Exception e) {
            logger.error("Unexpected error claiming reward", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getUserStats(final RoutingContext ctx) {
        final Long userId = getUserIdFromContext(ctx);
        userService.getUserStats(userId)
            .onSuccess(stats -> sendSuccess(ctx, stats))
            .onFailure(e -> {
                logger.error("Error getting user stats", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            });
    }

    public void getChatCost(final RoutingContext ctx) {
        chatService.getChatCost()
            .onSuccess(cost -> sendSuccess(ctx, new JsonObject().put("cost", cost)))
            .onFailure(e -> {
                logger.error("Error getting chat cost", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            });
    }

    public void startProfileChat(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Long profileId = Long.valueOf(ctx.pathParam("profileId"));

            chatService.startProfileChat(userId, profileId)
                .compose(chat -> userService.getUserById(userId).map(user -> {
                    final JsonObject response = new JsonObject()
                        .put("chat", ResponseMapper.toChatResponse(chat).getMap())
                        .put("cost", chat.getSettings() != null ? chat.getSettings().getCost() : 0);
                    user.ifPresent(value -> response.put("balance", value.getBalance()));
                    return response;
                }))
                .onSuccess(response -> sendSuccess(ctx, response))
                .onFailure(e -> {
                    final String message = e.getMessage() != null ? e.getMessage() : "Unable to start chat";
                    final String lower = message.toLowerCase();
                    if (lower.contains("insufficient")) {
                        sendError(ctx, 402, ErrorCodes.INSUFFICIENT_BALANCE, message);
                    } else if (lower.contains("not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, message);
                    } else {
                        sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, message);
                    }
                });
        } catch (final NumberFormatException e) {
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Invalid profileId");
        }
    }

    public void getChats(final RoutingContext ctx) {
        final Long userId = getUserIdFromContext(ctx);
        final int page = parseIntParam(ctx.request().getParam("page"), 1);
        final int limit = parseIntParam(ctx.request().getParam("limit"), 20);

        chatService.getUserChats(userId, page, limit)
            .compose(chats -> chatService.countUserChats(userId).map(total -> Map.entry(chats, total)))
            .onSuccess(result -> {
                final List<Map<String, Object>> chatResponses = result.getKey().stream()
                    .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                    .collect(Collectors.toList());
                sendPaginatedSuccess(ctx, chatResponses, page, limit, result.getValue());
            })
            .onFailure(e -> {
                logger.error("Error getting chats", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            });
    }

    public void getChat(final RoutingContext ctx) {
        final String chatId = ctx.pathParam("chatId");
        final Long userId = getUserIdFromContext(ctx);
        chatService.isUserInChat(chatId, userId)
            .compose(inChat -> {
                if (!inChat) {
                    return Future.failedFuture(new RuntimeException("Access denied"));
                }
                return chatService.getChatById(chatId);
            })
            .onSuccess(chat -> {
                if (chat.isEmpty()) {
                    sendError(ctx, 404, ErrorCodes.CHAT_NOT_FOUND, "Chat not found");
                } else {
                    sendSuccess(ctx, ResponseMapper.toChatResponse(chat.get()).getMap());
                }
            })
            .onFailure(e -> {
                if (e.getMessage() != null && e.getMessage().contains("Access denied")) {
                    sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                } else {
                    logger.error("Error getting chat: chatId={}", chatId, e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                }
            });
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
        final String chatId = ctx.pathParam("chatId");
        final Long userId = getUserIdFromContext(ctx);

        chatService.endChat(chatId, userId)
            .onSuccess(closedChat -> {
                webSocketHandler.notifyChatClosed(
                    closedChat.getId(),
                    closedChat.getClosedByUserId(),
                    closedChat.getClosureReason(),
                    closedChat.getClosedAt()
                );
                sendSuccess(ctx, new JsonObject()
                    .put("success", true)
                    .put("chat", ResponseMapper.toChatResponse(closedChat).getMap()));
            })
            .onFailure(e -> {
                logger.error("Error ending chat", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            });
    }

    public void getMessages(final RoutingContext ctx) {
        final String chatId = ctx.pathParam("chatId");
        final Long userId = getUserIdFromContext(ctx);
        final int page = parseIntParam(ctx.request().getParam("page"), 1);
        final int limit = parseIntParam(ctx.request().getParam("limit"), 50);

        chatService.isUserInChat(chatId, userId)
            .compose(inChat -> {
                if (!inChat) {
                    return Future.failedFuture(new RuntimeException("Access denied"));
                }
                return messageService.getChatMessages(chatId, page, limit)
                    .compose(messages -> messageService.countMessages(chatId).map(total -> Map.entry(messages, total)));
            })
            .onSuccess(result -> {
                final List<Map<String, Object>> messageResponses = result.getKey().stream()
                    .map(message -> ResponseMapper.toMessageResponse(message).getMap())
                    .collect(Collectors.toList());
                sendPaginatedSuccess(ctx, messageResponses, page, limit, Math.toIntExact(result.getValue()));
            })
            .onFailure(e -> {
                if (e.getMessage() != null && e.getMessage().contains("Access denied")) {
                    sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                } else {
                    logger.error("Error getting messages", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                }
            });
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
            final String clientMessageId = body.getString("clientMessageId");
            final JsonArray attachmentsJson = body.getJsonArray("attachments");
            final List<Message.MessageAttachment> attachments = parseAttachments(attachmentsJson);

            messageService.sendMessage(userId, chatId, text, replyToMessageId, attachments, clientMessageId)
                .compose(message -> {
                    final JsonObject messageJson = ResponseMapper.toMessageResponse(message);
                    return chatService.getChatById(chatId).compose(chatOpt -> {
                        if (chatOpt.isEmpty()) {
                            return Future.succeededFuture(messageJson);
                        }
                        final Chat chat = chatOpt.get();
                        final Long companionId = chat.getCompanionId(userId);
                        if (companionId == null) {
                            return Future.succeededFuture(messageJson);
                        }
                        webSocketHandler.sendMessageToUser(companionId, "message", messageJson.copy());
                        final Future<String> senderNameFuture = chat.getType() == Chat.ChatType.ANONYMOUS
                            ? Future.succeededFuture("Собеседник")
                            : resolveUserDisplayName(userId);
                        return senderNameFuture
                            .compose(senderDisplayName -> notificationService.sendNewMessageNotification(companionId, chat.getType(), senderDisplayName))
                            .map(notification -> messageJson);
                    });
                })
                .onSuccess(messageJson -> sendSuccess(ctx, messageJson.getMap()))
                .onFailure(e -> {
                    logger.error("Error sending message", e);
                    if (e.getMessage() != null && e.getMessage().contains("Insufficient balance")) {
                        sendError(ctx, 400, ErrorCodes.INSUFFICIENT_BALANCE, "Insufficient balance");
                    } else if (e.getMessage() != null && e.getMessage().contains("blocked")) {
                        sendError(ctx, 403, ErrorCodes.USER_BLOCKED, "User is blocked");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error sending message", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void editMessage(final RoutingContext ctx) {
        try {
            final String messageId = ctx.pathParam("messageId");
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final String text = body.getString("text");
            messageService.editMessage(messageId, userId, text)
                .onSuccess(editedMessage -> sendSuccess(ctx, ResponseMapper.toMessageResponse(editedMessage).getMap()))
                .onFailure(e -> {
                    logger.error("Error editing message", e);
                    if (e.getMessage() != null && e.getMessage().contains("not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Message not found");
                    } else if (e.getMessage() != null && e.getMessage().contains("only edit")) {
                        sendError(ctx, 403, ErrorCodes.FORBIDDEN, "No permission to edit message");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error editing message", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void deleteMessage(final RoutingContext ctx) {
        try {
            final String messageId = ctx.pathParam("messageId");
            final Long userId = getUserIdFromContext(ctx);

            messageService.deleteMessage(messageId, userId)
                .onSuccess(v -> sendSuccess(ctx, new JsonObject().put("success", true)))
                .onFailure(e -> {
                    logger.error("Error deleting message", e);
                    if (e.getMessage() != null && e.getMessage().contains("not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Message not found");
                    } else if (e.getMessage() != null && e.getMessage().contains("only delete")) {
                        sendError(ctx, 403, ErrorCodes.FORBIDDEN, "No permission to delete message");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error deleting message", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
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

            reportService.createReport(reporterId, targetId, chatId, messageId, reason, description)
                .onSuccess(report -> sendSuccess(ctx, ResponseMapper.toReportResponse(report).getMap()))
                .onFailure(e -> {
                    logger.error("Error creating report", e);
                    final String message = e.getMessage() != null ? e.getMessage() : "";
                    if (message.contains("not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User or resource not found");
                    } else if (message.contains("Report already exists")) {
                        sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Вы уже отправили жалобу");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error creating report", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getReports(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 20);

            userService.getUserById(userId)
                .compose(currentUser -> {
                    if (currentUser.isEmpty()) {
                        return Future.failedFuture(new RuntimeException("User not found"));
                    }
                    final Future<List<Report>> reportsFuture = currentUser.get().isAdmin()
                        ? reportService.getAllReports(page, limit)
                        : reportService.getUserReports(userId, page, limit);
                    final Future<Long> totalFuture = currentUser.get().isAdmin()
                        ? reportService.countReports()
                        : reportService.countUserReports(userId);
                    return reportsFuture.compose(reports ->
                        totalFuture.compose(total ->
                            buildReportPayload(reports).map(payload -> new ReportsResult(payload, Math.toIntExact(total)))));
                })
                .onSuccess(result -> sendPaginatedSuccess(ctx, result.payload(), page, limit, result.total()))
                .onFailure(e -> {
                    logger.error("Error getting reports", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                });
        } catch (final Exception e) {
            logger.error("Error getting reports", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void updateReportStatus(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
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

            userService.getUserById(userId)
                .compose(currentUser -> {
                    if (currentUser.isEmpty() || !currentUser.get().isAdmin()) {
                        return Future.failedFuture(new RuntimeException("Access denied"));
                    }
                    return reportService.updateReportStatus(reportId, status)
                        .compose(v -> reportService.getReportById(reportId));
                })
                .onSuccess(updatedReport -> {
                    if (updatedReport.isEmpty()) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Report not found");
                    } else {
                        sendSuccess(ctx, ResponseMapper.toReportResponse(updatedReport.get()).getMap());
                    }
                })
                .onFailure(e -> {
                    logger.error("Error updating report status", e);
                    if (e.getMessage() != null && e.getMessage().contains("Access denied")) {
                        sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                    } else if (e.getMessage() != null && e.getMessage().contains("Report not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Report not found");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error updating report status", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void blockUser(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final Long blockedUserId = body.getLong("userId");
            final String reason = body.getString("reason");

            blackListService.blockUser(userId, blockedUserId, reason)
                .compose(blackListItem -> chatService.closeChatsBetween(userId, blockedUserId, Chat.ChatClosureReason.BLOCKED)
                    .map(closedChats -> Map.entry(blackListItem, closedChats)))
                .onSuccess(result -> {
                    result.getValue().forEach(closedChat -> webSocketHandler.notifyChatClosed(
                        closedChat.getId(),
                        closedChat.getClosedByUserId(),
                        closedChat.getClosureReason(),
                        closedChat.getClosedAt()
                    ));
                    final JsonObject response = ResponseMapper.toBlackListItemResponse(result.getKey());
                    response.put("closedChats", result.getValue().stream()
                        .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                        .collect(Collectors.toList()));
                    sendSuccess(ctx, response);
                })
                .onFailure(e -> {
                    logger.error("Error blocking user", e);
                    if (e.getMessage() != null && e.getMessage().contains("not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
                    } else if (e.getMessage() != null && e.getMessage().contains("already blocked")) {
                        sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "User already blocked");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error blocking user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void unblockUser(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Long blockedUserId = Long.valueOf(ctx.pathParam("userId"));

            blackListService.unblockUser(userId, blockedUserId)
                .compose(v -> chatService.reopenChatsBetween(userId, blockedUserId))
                .onSuccess(reopenedChats -> {
                    reopenedChats.forEach(reopened -> webSocketHandler.notifyChatReopened(reopened.getId()));
                    sendSuccess(ctx, new JsonObject()
                        .put("success", true)
                        .put("reopenedChats", reopenedChats.stream()
                            .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                            .collect(Collectors.toList())));
                })
                .onFailure(e -> {
                    logger.error("Error unblocking user", e);
                    if (e.getMessage() != null && e.getMessage().contains("not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found in blacklist");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error unblocking user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getBlacklist(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 20);

            blackListService.getUserBlackList(userId, page, limit)
                .compose(blackList -> blackListService.getBlockedUsersCount(userId)
                    .map(total -> Map.entry(blackList, Math.toIntExact(total))))
                .onSuccess(result -> {
                    final List<Map<String, Object>> payload = result.getKey().stream()
                        .map(item -> ResponseMapper.toBlackListItemResponse(item).getMap())
                        .collect(Collectors.toList());
                    sendPaginatedSuccess(ctx, payload, page, limit, result.getValue());
                })
                .onFailure(e -> {
                    logger.error("Error getting blacklist", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                });
        } catch (final Exception e) {
            logger.error("Error getting blacklist", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getBlacklistStatus(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final Long blockedUserId = Long.valueOf(ctx.pathParam("userId"));
            blackListService.isUserBlocked(userId, blockedUserId)
                .onSuccess(blocked -> sendSuccess(ctx, new JsonObject().put("blocked", blocked)))
                .onFailure(e -> {
                    logger.error("Error getting blacklist status", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                });
        } catch (final Exception e) {
            logger.error("Error getting blacklist status", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void banUser(final RoutingContext ctx) {
        try {
            final Long adminId = getUserIdFromContext(ctx);
            final Long targetId = Long.valueOf(ctx.pathParam("userId"));
            final JsonObject body = ctx.getBodyAsJson();
            final String reason = body != null ? body.getString("reason") : null;

            userService.getUserById(adminId)
                .compose(admin -> {
                    if (admin.isEmpty() || !admin.get().isAdmin()) {
                        return Future.failedFuture(new RuntimeException("Access denied"));
                    }
                    return userService.banUser(targetId, reason)
                        .compose(bannedUser -> chatService.closeAllChatsForUser(targetId, Chat.ChatClosureReason.SYSTEM)
                            .map(closedChats -> Map.entry(bannedUser, closedChats)));
                })
                .onSuccess(result -> {
                    result.getValue().forEach(chat -> webSocketHandler.notifyChatClosed(
                        chat.getId(), chat.getClosedByUserId(), chat.getClosureReason(), chat.getClosedAt()
                    ));
                    sendSuccess(ctx, ResponseMapper.toUserResponse(result.getKey()).getMap());
                })
                .onFailure(e -> {
                    logger.error("Error banning user", e);
                    if (e.getMessage() != null && e.getMessage().contains("Access denied")) {
                        sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                    } else if (e.getMessage() != null && e.getMessage().contains("User not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error banning user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void unbanUser(final RoutingContext ctx) {
        try {
            final Long adminId = getUserIdFromContext(ctx);
            final Long targetId = Long.valueOf(ctx.pathParam("userId"));
            userService.getUserById(adminId)
                .compose(admin -> {
                    if (admin.isEmpty() || !admin.get().isAdmin()) {
                        return Future.failedFuture(new RuntimeException("Access denied"));
                    }
                    return userService.unbanUser(targetId);
                })
                .onSuccess(unbannedUser -> sendSuccess(ctx, ResponseMapper.toUserResponse(unbannedUser).getMap()))
                .onFailure(e -> {
                    logger.error("Error unbanning user", e);
                    if (e.getMessage() != null && e.getMessage().contains("Access denied")) {
                        sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                    } else if (e.getMessage() != null && e.getMessage().contains("User not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error unbanning user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
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
        final Long userId = getUserIdFromContext(ctx);
        subscriptionService.getActiveSubscription(userId)
            .onSuccess(subscription -> sendSuccess(ctx, subscription.orElse(null)))
            .onFailure(e -> {
                logger.error("Error getting active subscription", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            });
    }

    public void purchaseSubscription(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();

            final String planId = body.getString("planId");
            final String paymentMethodStr = body.getString("paymentMethod");
            final Subscription.PaymentMethod paymentMethod = Subscription.PaymentMethod.valueOf(paymentMethodStr.toUpperCase());

            subscriptionService.purchaseSubscription(userId, planId, paymentMethod)
                .onSuccess(subscription -> sendSuccess(ctx, subscription))
                .onFailure(e -> {
                    logger.error("Error purchasing subscription", e);
                    if (e.getMessage() != null && e.getMessage().contains("Insufficient balance")) {
                        sendError(ctx, 400, ErrorCodes.INSUFFICIENT_BALANCE, "Insufficient balance");
                    } else if (e.getMessage() != null && e.getMessage().contains("already active")) {
                        sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Subscription already active");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error purchasing subscription", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void cancelSubscription(final RoutingContext ctx) {
        final Long userId = getUserIdFromContext(ctx);
        subscriptionService.cancelSubscription(userId)
            .onSuccess(v -> sendSuccess(ctx, new JsonObject().put("success", true)))
            .onFailure(e -> {
                logger.error("Error cancelling subscription", e);
                if (e.getMessage() != null && e.getMessage().contains("no active subscription")) {
                    sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "No active subscription to cancel");
                } else {
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                }
            });
    }

    public void getNotifications(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final int page = parseIntParam(ctx.request().getParam("page"), 1);
            final int limit = parseIntParam(ctx.request().getParam("limit"), 20);

            notificationService.getUserNotifications(userId, page, limit)
                .compose(notifications -> notificationService.countUserNotifications(userId)
                    .map(total -> Map.entry(notifications, total)))
                .onSuccess(result -> sendPaginatedSuccess(ctx, result.getKey(), page, limit, result.getValue()))
                .onFailure(e -> {
                    logger.error("Error getting notifications", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                });
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

            notificationService.markNotificationsAsRead(notificationIds)
                .onSuccess(v -> sendSuccess(ctx, new JsonObject().put("success", true)))
                .onFailure(e -> {
                    logger.error("Error marking notifications as read", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                });
        } catch (final Exception e) {
            logger.error("Error marking notifications as read", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void deleteNotification(final RoutingContext ctx) {
        try {
            final String notificationId = ctx.pathParam("notificationId");
            final Long userId = getUserIdFromContext(ctx);

            notificationService.deleteNotification(notificationId, userId)
                .onSuccess(v -> sendSuccess(ctx, new JsonObject().put("success", true)))
                .onFailure(e -> {
                    logger.error("Error deleting notification", e);
                    if (e.getMessage() != null && e.getMessage().contains("not found")) {
                        sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Notification not found");
                    } else {
                        sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                    }
                });
        } catch (final Exception e) {
            logger.error("Error deleting notification", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void updateCommunityNotifications(final RoutingContext ctx) {
        try {
            final Long userId = getUserIdFromContext(ctx);
            final JsonObject body = ctx.getBodyAsJson();
            final boolean enabled = body == null || body.getBoolean("enabled", true);

            userService.updateCommunityNotifications(userId, enabled)
                .compose(user -> {
                    if (!enabled) {
                        return Future.succeededFuture(new CommunityNotificationsResult(false, null));
                    }
                    return notificationService.sendCommunityTestNotification(userId)
                        .compose(testSent -> userService.claimReward(userId, UserService.RewardType.COMMUNITY, true)
                            .map(reward -> new CommunityNotificationsResult(testSent, reward))
                            .otherwise(error -> {
                                logger.info("Community reward not issued for user {}: {}", userId, error.getMessage());
                                return new CommunityNotificationsResult(testSent, null);
                            }));
                })
                .onSuccess(result -> {
                    final JsonObject response = new JsonObject()
                        .put("enabled", enabled)
                        .put("testSent", result.testSent());
                    if (result.rewardResult() != null) {
                        response.put("rewarded", result.rewardResult().rewardedAmount())
                            .put("balance", result.rewardResult().balance())
                            .put("rewards", toRewardsJson(result.rewardResult().rewards()));
                    }
                    sendSuccess(ctx, response);
                })
                .onFailure(e -> {
                    logger.error("Error updating community notifications", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Failed to update community notifications");
                });
        } catch (final Exception e) {
            logger.error("Error updating community notifications", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Failed to update community notifications");
        }
    }

    public void getOnlineStats(final RoutingContext ctx) {
        try {
            userService.getOnlineStats()
                .map(stats -> new JsonObject()
                    .put("anonymousSearchUsers", chatService.getSearchQueueSize())
                    .put("totalUsers", stats.totalUsers())
                    .put("activeUsers", stats.activeUsers()))
                .onSuccess(response -> sendSuccess(ctx, response))
                .onFailure(e -> {
                    logger.error("Error getting online stats", e);
                    sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
                });
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

    private Future<String> resolveUserDisplayName(final Long userId) {
        if (userId == null) {
            return Future.succeededFuture("Пользователь");
        }
        return userService.getUserById(userId)
            .map(user -> user.map(value -> {
                final String first = value.getFirstName() != null ? value.getFirstName().trim() : "";
                final String last = value.getLastName() != null ? value.getLastName().trim() : "";
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
            }).orElse("Пользователь #" + userId));
    }

    private Future<Boolean> shouldMaskUser(final Long viewerId, final User target) {
        if (target == null || target.getId() == null) {
            return Future.succeededFuture(true);
        }

        if (target.getId().equals(viewerId)) {
            return Future.succeededFuture(false);
        }

        if (viewerId != null) {
            return userService.getUserById(viewerId)
                .compose(viewer -> {
                    if (viewer.isPresent() && viewer.get().isAdmin()) {
                        return Future.succeededFuture(false);
                    }
                    return chatService.hasRegularChatBetween(viewerId, target.getId()).map(hasChat -> !hasChat);
                });
        }

        return Future.succeededFuture(true);
    }

    private Future<List<Map<String, Object>>> buildReportPayload(final List<Report> reports) {
        if (reports == null || reports.isEmpty()) {
            return Future.succeededFuture(List.of());
        }
        return Future.succeededFuture(reports.stream()
            .map(report -> ResponseMapper.toReportResponse(report).getMap())
            .toList());
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

    private record ReportsResult(List<Map<String, Object>> payload, int total) {
    }

    private record CommunityNotificationsResult(boolean testSent, UserService.RewardClaimResult rewardResult) {
    }

}
