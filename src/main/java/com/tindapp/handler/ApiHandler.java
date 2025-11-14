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

        ErrorCodes(String code) {
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

    public ApiHandler(UserService userService, ChatService chatService, MessageService messageService,
                     NotificationService notificationService, SubscriptionService subscriptionService,
                     ReportService reportService, BlackListService blackListService,
                     WebSocketHandler webSocketHandler, LocationService locationService,
                     ProfileService profileService) {
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

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void getCountries(RoutingContext ctx) {
        try {
            List<LocationService.Country> countries = locationService.getCountries();
            JsonArray countriesArray = new JsonArray(
                countries.stream()
                    .map(country -> new JsonObject()
                        .put("id", country.getId())
                        .put("name", country.getName()))
                    .collect(Collectors.toList())
            );
            JsonObject payload = new JsonObject().put("countries", countriesArray);
            sendSuccess(ctx, payload);
        } catch (Exception e) {
            logger.error("Error fetching countries", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getCitiesByCountry(RoutingContext ctx) {
        try {
            String countryId = ctx.pathParam("countryId");

            if (countryId == null || countryId.isEmpty()) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "countryId is required");
                return;
            }

            String query = ctx.request().getParam("query");
            if (query == null) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "query parameter is required");
                return;
            }

            String trimmedQuery = query.trim();
            if (trimmedQuery.length() < 3) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "query parameter must be at least 3 characters");
                return;
            }

            List<LocationService.City> cities = locationService.searchCitiesByCountry(countryId, trimmedQuery);
            JsonArray citiesArray = new JsonArray(
                cities.stream()
                    .map(city -> new JsonObject()
                        .put("id", city.getId())
                        .put("name", city.getName()))
                    .collect(Collectors.toList())
            );
            JsonObject payload = new JsonObject()
                .put("countryId", countryId)
                .put("query", trimmedQuery)
                .put("cities", citiesArray);
            sendSuccess(ctx, payload);
        } catch (Exception e) {
            logger.error("Error fetching cities for country", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getProfiles(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = parseIntParam(ctx.request().getParam("page"), 1);
            int limit = parseIntParam(ctx.request().getParam("limit"), 12);

            User viewer = userService.getUserById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

            ProfileService.ProfileFilters filters = profileService.parseFilters(ctx.request().params(), viewer);
            ProfileService.ProfileSearchResult result = profileService.searchProfiles(viewer, filters, page, limit);

            sendPaginatedSuccess(ctx, result.getProfiles(), page, limit, result.getTotal());
        } catch (NumberFormatException e) {
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Invalid pagination parameters");
        } catch (RuntimeException e) {
            if (e.getMessage().contains("User not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, e.getMessage());
            } else {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, e.getMessage());
            }
        } catch (Exception e) {
            logger.error("Error getting profiles", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getCurrentUser(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Optional<User> user = userService.getUserById(userId);

            if (user.isPresent()) {
                JsonObject payload = ResponseMapper.toUserResponse(user.get());
                payload.put("isAnonymousForViewer", false);
                sendSuccess(ctx, payload.getMap());
            } else {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            }
        } catch (Exception e) {
            logger.error("Error getting current user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getUser(RoutingContext ctx) {
        try {
            Long userId = Long.valueOf(ctx.pathParam("userId"));
            Long viewerId = getUserIdFromContext(ctx);
            Optional<User> user = userService.getUserById(userId);

            if (user.isPresent()) {
                User target = user.get();
                JsonObject payload = ResponseMapper.toUserResponse(target);
                boolean isAnonymous = shouldMaskUser(viewerId, target);
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
        } catch (Exception e) {
            logger.error("Error getting user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void updateProfile(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            String firstName = trimToNull(body.getString("firstName"));
            String lastName = trimToNull(body.getString("lastName"));
            String avatarUrl = trimToNull(body.getString("avatarUrl"));
            String gender = normalizeGender(body.getString("gender"));
            String bio = trimToNull(body.getString("bio"));
            String country = trimToNull(body.getString("country"));
            String city = trimToNull(body.getString("city"));
            Integer age = body.getInteger("age");
            String birthDate = trimToNull(body.getString("birthDate"));
            Boolean isVisible = body.getBoolean("isVisible");
            Integer profileCost = body.getInteger("profileCost");

            User.UserSettings settings = null;
            JsonObject settingsJson = body.getJsonObject("settings");
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

            User updatedUser = userService.updateProfile(
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
                profileCost
            );
            sendSuccess(ctx, ResponseMapper.toUserResponse(updatedUser).getMap());
            webSocketHandler.notifyProfileUpdated(updatedUser);
        } catch (Exception e) {
            logger.error("Error updating profile", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, e.getMessage());
        }
    }

    public void verifyUser(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            User verifiedUser = userService.verifyUser(userId);

            JsonObject response = new JsonObject()
                .put("isVerified", verifiedUser.getIsVerified());
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error verifying user", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeGender(String gender) {
        if (gender == null) {
            return null;
        }
        String normalized = gender.trim().toLowerCase();
        switch (normalized) {
            case "male":
            case "female":
            case "other":
                return normalized;
            default:
                return null;
        }
    }

    public void getBalance(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Integer balance = userService.getUserBalance(userId);

            JsonObject response = new JsonObject().put("balance", balance);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error getting user balance", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void purchaseCoins(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            Integer amount = body.getInteger("amount");
            String paymentMethod = body.getString("paymentMethod");

            User updatedUser = userService.purchaseCoins(userId, amount, paymentMethod);

            JsonObject response = new JsonObject().put("balance", updatedUser.getBalance());
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error purchasing coins", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getUserStats(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            UserService.UserStats stats = userService.getUserStats(userId);
            sendSuccess(ctx, stats);
        } catch (Exception e) {
            logger.error("Error getting user stats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChatCost(RoutingContext ctx) {
        try {
            int cost = chatService.getChatCost();
            JsonObject response = new JsonObject().put("cost", cost);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error getting chat cost", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void startProfileChat(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Long profileId = Long.valueOf(ctx.pathParam("profileId"));

            Chat chat = chatService.startProfileChat(userId, profileId);
            JsonObject response = new JsonObject()
                .put("chat", ResponseMapper.toChatResponse(chat).getMap())
                .put("cost", chat.getSettings() != null ? chat.getSettings().getCost() : 0);

            userService.getUserById(userId).ifPresent(user -> response.put("balance", user.getBalance()));

            sendSuccess(ctx, response);
        } catch (NumberFormatException e) {
            sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Invalid profileId");
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : "Unable to start chat";
            String lower = message.toLowerCase();
            if (lower.contains("insufficient")) {
                sendError(ctx, 402, ErrorCodes.INSUFFICIENT_BALANCE, message);
            } else if (lower.contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, message);
            } else {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, message);
            }
        } catch (Exception e) {
            logger.error("Error starting profile chat", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChats(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "20"));

            List<Chat> chats = chatService.getUserChats(userId, page, limit);
            List<Map<String, Object>> chatResponses = chats.stream()
                .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                .collect(Collectors.toList());

            sendPaginatedSuccess(ctx, chatResponses, page, limit, chats.size());
        } catch (Exception e) {
            logger.error("Error getting chats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getChat(RoutingContext ctx) {
        try {
            String chatId = ctx.pathParam("chatId");
            Long userId = getUserIdFromContext(ctx);

            logger.info("Getting chat: chatId={}, userId={}", chatId, userId);

            if (!chatService.isUserInChat(chatId, userId)) {
                logger.warn("User {} denied access to chat {}", userId, chatId);
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            Optional<Chat> chat = chatService.getChatById(chatId);
            if (chat.isPresent()) {
                logger.info("Chat found and returned: chatId={}", chatId);
                sendSuccess(ctx, ResponseMapper.toChatResponse(chat.get()).getMap());
            } else {
                logger.warn("Chat not found: chatId={}", chatId);
                sendError(ctx, 404, ErrorCodes.CHAT_NOT_FOUND, "Chat not found");
            }
        } catch (Exception e) {
            logger.error("Error getting chat: chatId={}", ctx.pathParam("chatId"), e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getSearchStatus(RoutingContext ctx) {
        logger.info("=== getSearchStatus method called ===");
        logger.info("Request path: {}, method: {}", ctx.request().path(), ctx.request().method());
        logger.info("Headers: Authorization={}", ctx.request().getHeader("Authorization"));
        try {

            Long userId = getUserIdFromContext(ctx);

            boolean isSearching = chatService.isSearchingCompanion(userId);
            int queueSize = chatService.getSearchQueueSize();

            JsonObject response = new JsonObject()
                .put("isSearching", isSearching)
                .put("queueSize", queueSize);

            sendSuccess(ctx, response);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("User ID not found in context")) {
                logger.error("Authentication error in getSearchStatus: context has currentUser={}, userId={}",
                           ctx.get("currentUser"), ctx.get("userId"), e);
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
            } else {
                logger.error("Runtime error in getSearchStatus", e);
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        } catch (Exception e) {
            logger.error("Error getting search status", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void endChat(RoutingContext ctx) {
        try {
            String chatId = ctx.pathParam("chatId");
            Long userId = getUserIdFromContext(ctx);

            Chat closedChat = chatService.endChat(chatId, userId);
            if (closedChat != null) {
                webSocketHandler.notifyChatClosed(
                    closedChat.getId(),
                    closedChat.getClosedByUserId(),
                    closedChat.getClosureReason(),
                    closedChat.getClosedAt()
                );
            }

            JsonObject response = new JsonObject()
                .put("success", true)
                .put("chat", ResponseMapper.toChatResponse(closedChat).getMap());
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error ending chat", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getMessages(RoutingContext ctx) {
        try {
            String chatId = ctx.pathParam("chatId");
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "50"));

            if (!chatService.isUserInChat(chatId, userId)) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            List<Message> messages = messageService.getChatMessages(chatId, page, limit);
            List<Map<String, Object>> messageResponses = messages.stream()
                .map(message -> ResponseMapper.toMessageResponse(message).getMap())
                .collect(Collectors.toList());

            sendPaginatedSuccess(ctx, messageResponses, page, limit, messages.size());
        } catch (Exception e) {
            logger.error("Error getting messages", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void uploadImage(RoutingContext ctx) {
        FileUpload upload = null;
        try {
            Long userId = getUserIdFromContext(ctx);
            if (userId == null) {
                sendError(ctx, 401, ErrorCodes.UNAUTHORIZED, "Not authenticated");
                return;
            }

            List<FileUpload> uploads = ctx.fileUploads();
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

            String contentType = upload.contentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Only image uploads are allowed");
                deleteTempFile(upload);
                return;
            }

            String originalFileName = upload.fileName();
            String extension = getFileExtension(originalFileName);
            if (extension.isEmpty()) {
                extension = getExtensionFromContentType(contentType);
            }

            if (extension.isEmpty()) {
                extension = ".jpg";
            }

            String fileName = UUID.randomUUID() + extension;
            Path targetPath = Paths.get(AppConfig.UPLOAD_DIR, fileName);

            Files.move(Paths.get(upload.uploadedFileName()), targetPath, StandardCopyOption.REPLACE_EXISTING);

            JsonObject data = new JsonObject()
                .put("url", "/uploads/" + fileName)
                .put("preview", "/uploads/" + fileName);

            sendSuccess(ctx, data);
        } catch (Exception e) {
            logger.error("Error uploading image", e);
            if (upload != null) {
                deleteTempFile(upload);
            }
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Failed to upload image");
        }
    }

    public void sendMessage(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            String chatId = body.getString("chatId");
            String text = body.getString("text", "");
            String replyToMessageId = body.getString("replyToMessageId");
            JsonArray attachmentsJson = body.getJsonArray("attachments");
            List<Message.MessageAttachment> attachments = parseAttachments(attachmentsJson);

            Message message = messageService.sendMessage(userId, chatId, text, replyToMessageId, attachments);
            JsonObject messageJson = ResponseMapper.toMessageResponse(message);
            String senderName = resolveUserDisplayName(userId);

            logger.info("Broadcasting message via WebSocket: chatId={}, messageId={}", chatId, message.getId());
            webSocketHandler.sendMessageToUser(userId, "message", messageJson.copy());

            Optional<Chat> chatOpt = chatService.getChatById(chatId);
            if (chatOpt.isPresent()) {
                Chat chat = chatOpt.get();
                Long companionId = chat.getCompanionId(userId);
                if (companionId != null) {
                    webSocketHandler.sendMessageToUser(companionId, "message", messageJson.copy());
                    String senderDisplayName =
                        chat.getType() == Chat.ChatType.ANONYMOUS
                            ? "Собеседник"
                            : senderName;
                    notificationService.sendNewMessageNotification(companionId, chat.getType(), senderDisplayName);
                }
            }

            sendSuccess(ctx, messageJson.getMap());
        } catch (Exception e) {
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

    public void editMessage(RoutingContext ctx) {
        try {
            String messageId = ctx.pathParam("messageId");
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            String text = body.getString("text");
            Message editedMessage = messageService.editMessage(messageId, userId, text);
            sendSuccess(ctx, ResponseMapper.toMessageResponse(editedMessage).getMap());
        } catch (Exception e) {
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

    public void deleteMessage(RoutingContext ctx) {
        try {
            String messageId = ctx.pathParam("messageId");
            Long userId = getUserIdFromContext(ctx);

            messageService.deleteMessage(messageId, userId);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
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

    public void createReport(RoutingContext ctx) {
        try {
            Long reporterId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            Long targetId = body.getLong("targetId");
            String chatId = body.getString("chatId");
            String messageId = body.getString("messageId");
            String reasonStr = body.getString("reason");
            String description = body.getString("description");

            Report.ReportReason reason = Report.ReportReason.OTHER;
            if (reasonStr != null && !reasonStr.trim().isEmpty()) {
                try {
                    reason = Report.ReportReason.valueOf(reasonStr.trim().toUpperCase());
                } catch (Exception ignored) {
                    reason = Report.ReportReason.OTHER;
                }
            }

            Report report = reportService.createReport(reporterId, targetId, chatId, messageId, reason, description);
            sendSuccess(ctx, ResponseMapper.toReportResponse(report).getMap());
        } catch (Exception e) {
            logger.error("Error creating report", e);
            String message = e.getMessage() != null ? e.getMessage() : "";
            if (message.contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User or resource not found");
            } else if (message.contains("Report already exists")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Вы уже отправили жалобу");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getReports(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "20"));

            User currentUser = userService.getUserById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

            List<Report> reports;
            int total;
            if (currentUser.isAdmin()) {
                reports = reportService.getAllReports(page, limit);
                total = (int) reportService.countReports();
            } else {
                reports = reportService.getUserReports(userId, page, limit);
                total = reports.size();
            }

            List<Map<String, Object>> payload = new ArrayList<>();
            for (Report report : reports) {
                JsonObject reportJson = ResponseMapper.toReportResponse(report);
                if (report.getChatId() != null) {
                    List<Message> lastMessages = messageService.getRecentMessages(report.getChatId(), 5);
                    List<Map<String, Object>> mappedMessages = lastMessages.stream()
                        .map(message -> ResponseMapper.toMessageResponse(message).getMap())
                        .collect(Collectors.toList());
                    reportJson.put("lastMessages", mappedMessages);
                }
                payload.add(reportJson.getMap());
            }

            sendPaginatedSuccess(ctx, payload, page, limit, total);
        } catch (Exception e) {
            logger.error("Error getting reports", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void updateReportStatus(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            User currentUser = userService.getUserById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

            if (!currentUser.isAdmin()) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            String reportId = ctx.pathParam("reportId");
            JsonObject body = ctx.getBodyAsJson();
            if (body == null || !body.containsKey("status")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Status is required");
                return;
            }

            String statusValue = body.getString("status");
            Report.ReportStatus status;
            try {
                status = Report.ReportStatus.valueOf(statusValue.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "Invalid status value");
                return;
            }

            reportService.updateReportStatus(reportId, status);
            Report updatedReport = reportService.getReportById(reportId)
                .orElseThrow(() -> new RuntimeException("Report not found"));

            JsonObject payload = ResponseMapper.toReportResponse(updatedReport);
            sendSuccess(ctx, payload.getMap());
        } catch (Exception e) {
            logger.error("Error updating report status", e);
            if (e.getMessage() != null && e.getMessage().contains("Report not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Report not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void blockUser(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            Long blockedUserId = body.getLong("userId");
            String reason = body.getString("reason");

            BlackListItem blackListItem = blackListService.blockUser(userId, blockedUserId, reason);
            List<Chat> closedChats = chatService.closeChatsBetween(
                userId,
                blockedUserId,
                Chat.ChatClosureReason.BLOCKED
            );

            for (Chat closedChat : closedChats) {
                webSocketHandler.notifyChatClosed(
                    closedChat.getId(),
                    closedChat.getClosedByUserId(),
                    closedChat.getClosureReason(),
                    closedChat.getClosedAt()
                );
            }

            JsonObject response = ResponseMapper.toBlackListItemResponse(blackListItem);
            response.put(
                "closedChats",
                closedChats.stream()
                    .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                    .collect(Collectors.toList())
            );
            sendSuccess(ctx, response);
        } catch (Exception e) {
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

    public void unblockUser(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Long blockedUserId = Long.valueOf(ctx.pathParam("userId"));

            blackListService.unblockUser(userId, blockedUserId);

            List<Chat> reopenedChats = chatService.reopenChatsBetween(userId, blockedUserId);
            for (Chat reopened : reopenedChats) {
                webSocketHandler.notifyChatReopened(reopened.getId());
            }

            JsonObject response = new JsonObject()
                .put("success", true)
                .put(
                    "reopenedChats",
                    reopenedChats.stream()
                        .map(chat -> ResponseMapper.toChatResponse(chat).getMap())
                        .collect(Collectors.toList())
                );
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error unblocking user", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found in blacklist");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getBlacklist(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "20"));

            List<BlackListItem> blackList = blackListService.getUserBlackList(userId, page, limit);
            List<Map<String, Object>> payload = blackList.stream()
                .map(item -> ResponseMapper.toBlackListItemResponse(item).getMap())
                .collect(Collectors.toList());
            sendPaginatedSuccess(ctx, payload, page, limit, blackList.size());
        } catch (Exception e) {
            logger.error("Error getting blacklist", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void banUser(RoutingContext ctx) {
        try {
            Long adminId = getUserIdFromContext(ctx);
            User admin = userService.getUserById(adminId)
                .orElseThrow(() -> new RuntimeException("User not found"));
            if (!admin.isAdmin()) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            Long targetId = Long.valueOf(ctx.pathParam("userId"));
            JsonObject body = ctx.getBodyAsJson();
            String reason = body != null ? body.getString("reason") : null;

            User bannedUser = userService.banUser(targetId, reason);
            List<Chat> closedChats = chatService.closeAllChatsForUser(targetId, Chat.ChatClosureReason.SYSTEM);
            for (Chat chat : closedChats) {
                webSocketHandler.notifyChatClosed(
                    chat.getId(),
                    chat.getClosedByUserId(),
                    chat.getClosureReason(),
                    chat.getClosedAt()
                );
            }

            sendSuccess(ctx, ResponseMapper.toUserResponse(bannedUser).getMap());
        } catch (Exception e) {
            logger.error("Error banning user", e);
            if (e.getMessage() != null && e.getMessage().contains("User not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void unbanUser(RoutingContext ctx) {
        try {
            Long adminId = getUserIdFromContext(ctx);
            User admin = userService.getUserById(adminId)
                .orElseThrow(() -> new RuntimeException("User not found"));
            if (!admin.isAdmin()) {
                sendError(ctx, 403, ErrorCodes.FORBIDDEN, "Access denied");
                return;
            }

            Long targetId = Long.valueOf(ctx.pathParam("userId"));
            User unbannedUser = userService.unbanUser(targetId);
            sendSuccess(ctx, ResponseMapper.toUserResponse(unbannedUser).getMap());
        } catch (Exception e) {
            logger.error("Error unbanning user", e);
            if (e.getMessage() != null && e.getMessage().contains("User not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "User not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getSubscriptionPlans(RoutingContext ctx) {
        try {
            List<SubscriptionService.SubscriptionPlan> plans = subscriptionService.getAvailablePlans();
            sendSuccess(ctx, plans);
        } catch (Exception e) {
            logger.error("Error getting subscription plans", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getActiveSubscription(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            Optional<Subscription> subscription = subscriptionService.getActiveSubscription(userId);

            if (subscription.isPresent()) {
                sendSuccess(ctx, subscription.get());
            } else {
                sendSuccess(ctx, null);
            }
        } catch (Exception e) {
            logger.error("Error getting active subscription", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void purchaseSubscription(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();

            String planId = body.getString("planId");
            String paymentMethodStr = body.getString("paymentMethod");
            Subscription.PaymentMethod paymentMethod = Subscription.PaymentMethod.valueOf(paymentMethodStr.toUpperCase());

            Subscription subscription = subscriptionService.purchaseSubscription(userId, planId, paymentMethod);
            sendSuccess(ctx, subscription);
        } catch (Exception e) {
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

    public void cancelSubscription(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            subscriptionService.cancelSubscription(userId);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error cancelling subscription", e);
            if (e.getMessage().contains("no active subscription")) {
                sendError(ctx, 400, ErrorCodes.VALIDATION_ERROR, "No active subscription to cancel");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void getNotifications(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            int page = Integer.parseInt(ctx.request().getParam("page", "1"));
            int limit = Integer.parseInt(ctx.request().getParam("limit", "20"));

            List<Notification> notifications = notificationService.getUserNotifications(userId, page, limit);
            sendPaginatedSuccess(ctx, notifications, page, limit, notifications.size());
        } catch (Exception e) {
            logger.error("Error getting notifications", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void markNotificationsAsRead(RoutingContext ctx) {
        try {
            JsonObject body = ctx.getBodyAsJson();
            List<String> notificationIds = body.getJsonArray("notificationIds")
                .stream()
                .map(Object::toString)
                .collect(Collectors.toList());

            notificationService.markNotificationsAsRead(notificationIds);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error marking notifications as read", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void deleteNotification(RoutingContext ctx) {
        try {
            String notificationId = ctx.pathParam("notificationId");
            Long userId = getUserIdFromContext(ctx);

            notificationService.deleteNotification(notificationId, userId);

            JsonObject response = new JsonObject().put("success", true);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error deleting notification", e);
            if (e.getMessage().contains("not found")) {
                sendError(ctx, 404, ErrorCodes.NOT_FOUND, "Notification not found");
            } else {
                sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
            }
        }
    }

    public void updateCommunityNotifications(RoutingContext ctx) {
        try {
            Long userId = getUserIdFromContext(ctx);
            JsonObject body = ctx.getBodyAsJson();
            boolean enabled = body == null || body.getBoolean("enabled", true);

            userService.updateCommunityNotifications(userId, enabled);
            boolean testSent = false;
            if (enabled) {
                testSent = notificationService.sendCommunityTestNotification(userId);
            }

            JsonObject response = new JsonObject()
                .put("enabled", enabled)
                .put("testSent", testSent);
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error updating community notifications", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Failed to update community notifications");
        }
    }

    public void getOnlineStats(RoutingContext ctx) {
        try {
            UserService.OnlineStats stats = userService.getOnlineStats();
            JsonObject response = new JsonObject()
                .put("anonymousChats", chatService.getActiveAnonymousChatsCount())
                .put("totalUsers", stats.getTotalUsers())
                .put("activeUsers", stats.getActiveUsers());
            sendSuccess(ctx, response);
        } catch (Exception e) {
            logger.error("Error getting online stats", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    public void getAppConfig(RoutingContext ctx) {
        try {
            JsonObject config = AppConfig.getClientConfig();
            sendSuccess(ctx, config);
        } catch (Exception e) {
            logger.error("Error getting app config", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    private Long getUserIdFromContext(RoutingContext ctx) {
        Long userId = ctx.get("userId");
        if (userId == null) {
            throw new RuntimeException("User ID not found in context");
        }
        return userId;
    }

    private int parseIntParam(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw e;
        }
    }

    private void deleteTempFile(FileUpload upload) {
        try {
            Files.deleteIfExists(Paths.get(upload.uploadedFileName()));
        } catch (Exception ignored) {
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase();
    }

    private String getExtensionFromContentType(String contentType) {
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

    private List<Message.MessageAttachment> parseAttachments(JsonArray attachmentsJson) {
        List<Message.MessageAttachment> attachments = new ArrayList<>();
        if (attachmentsJson == null || attachmentsJson.isEmpty()) {
            return attachments;
        }

        for (int i = 0; i < attachmentsJson.size(); i++) {
            Object raw = attachmentsJson.getValue(i);
            if (!(raw instanceof JsonObject)) {
                continue;
            }
            JsonObject attachmentJson = (JsonObject) raw;
            String typeString = attachmentJson.getString("type", "image");
            Message.MessageAttachment.AttachmentType type = parseAttachmentType(typeString);
            String url = attachmentJson.getString("url");
            if (url == null || url.isEmpty()) {
                continue;
            }
            String preview = attachmentJson.getString("preview", url);
            Message.MessageAttachment attachment = new Message.MessageAttachment(type, url, preview);
            attachments.add(attachment);
        }
        return attachments;
    }

    private Message.MessageAttachment.AttachmentType parseAttachmentType(String typeString) {
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

    private String resolveUserDisplayName(Long userId) {
        if (userId == null) {
            return "Пользователь";
        }
        return userService.getUserById(userId)
            .map(user -> {
                String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
                String last = user.getLastName() != null ? user.getLastName().trim() : "";
                String full = (first + " " + last).trim();
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

    private boolean shouldMaskUser(Long viewerId, User target) {
        if (target == null || target.getId() == null) {
            return true;
        }

        if (viewerId != null && target.getId().equals(viewerId)) {
            return false;
        }

        if (viewerId != null) {
            Optional<User> viewer = userService.getUserById(viewerId);
            if (viewer.isPresent() && Boolean.TRUE.equals(viewer.get().isAdmin())) {
                return false;
            }
            if (chatService.hasRegularChatBetween(viewerId, target.getId())) {
                return false;
            }
        }

        return true;
    }

    private void sendSuccess(RoutingContext ctx, Object data) {
        try {
            JsonObject response;
            if (data != null) {
                Object parsedData;

                if (data instanceof JsonObject || data instanceof JsonArray) {
                    parsedData = data;
                } else if (data instanceof Iterable) {
                    JsonArray array = new JsonArray();
                    for (Object item : (Iterable<?>) data) {
                        array.add(item);
                    }
                    parsedData = array;
                } else if (data.getClass().isArray()) {
                    JsonArray array = new JsonArray();
                    int length = java.lang.reflect.Array.getLength(data);
                    for (int i = 0; i < length; i++) {
                        array.add(java.lang.reflect.Array.get(data, i));
                    }
                    parsedData = array;
                } else {
                    String dataJson = objectMapper.writeValueAsString(data);
                    String trimmed = dataJson.trim();
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
        } catch (Exception e) {
            logger.error("Error sending success response", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Internal server error");
        }
    }

    private void sendPaginatedSuccess(RoutingContext ctx, List<?> data, int page, int limit, int total) {
        try {
            JsonObject pagination = new JsonObject()
                .put("page", page)
                .put("limit", limit)
                .put("total", total)
                .put("totalPages", (int) Math.ceil((double) total / limit));

            String dataJson = objectMapper.writeValueAsString(data);
            io.vertx.core.json.JsonArray parsedData = new io.vertx.core.json.JsonArray(dataJson);

            JsonObject response = new JsonObject()
                .put("success", true)
                .put("data", parsedData)
                .put("pagination", pagination);

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            logger.error("Error sending paginated response", e);
            sendError(ctx, 500, ErrorCodes.SERVER_ERROR, "Error serializing response");
        }
    }

    private void sendError(RoutingContext ctx, int statusCode, ErrorCodes errorCode, String message) {
        JsonObject error = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("code", errorCode.getCode());

        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(error.encode());
    }

}
