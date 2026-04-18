package com.tindapp.handler;

import com.tindapp.model.Notification;
import com.tindapp.model.Subscription;
import com.tindapp.model.User;
import com.tindapp.service.NotificationService;
import com.tindapp.service.SubscriptionService;
import com.tindapp.service.UserService;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public class VkPaymentHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(VkPaymentHandler.class);
    private static final String DEFAULT_PHOTO_URL = System.getenv("SUBSCRIPTION_PHOTO_URL");

    private final String clientSecret;
    private final SubscriptionService subscriptionService;
    private final UserService userService;
    private final NotificationService notificationService;
    private final WebSocketHandler webSocketHandler;

    public VkPaymentHandler(final String clientSecret,
                            final SubscriptionService subscriptionService,
                            final UserService userService,
                            final NotificationService notificationService,
                            final WebSocketHandler webSocketHandler) {
        this.clientSecret = clientSecret;
        this.subscriptionService = subscriptionService;
        this.userService = userService;
        this.notificationService = notificationService;
        this.webSocketHandler = webSocketHandler;
    }

    @Override
    public void handle(final RoutingContext ctx) {
        try {
            final String rawBody = ctx.getBodyAsString();
            Map<String, String> params = parseFormBody(rawBody);

            if (params.isEmpty()) {
                final Map<String, String> formParams = new HashMap<>();
                ctx.request().formAttributes().forEach(entry ->
                    formParams.put(entry.getKey(), entry.getValue())
                );
                params = formParams;
            }

            if (params.isEmpty()) {
                sendError(ctx, 11, "Empty request body", true);
                return;
            }

            final String signature = params.get("sig");
            if (signature == null) {
                sendError(ctx, 11, "Missing signature", true);
                return;
            }

            if (!verifySignature(params, signature)) {
                sendError(ctx, 10, "Invalid signature", true);
                return;
            }

            final String notificationType = params.get("notification_type");
            if (notificationType == null) {
                sendError(ctx, 11, "Missing notification type", true);
                return;
            }

            switch (notificationType) {
                case "get_subscription":
                case "get_subscription_test":
                    handleGetSubscription(ctx, params);
                    break;
                case "subscription_status_change":
                case "subscription_status_change_test":
                    handleSubscriptionStatusChange(ctx, params);
                    break;
                default:
                    logger.warn("Unsupported VK payment notification: {}", notificationType);
                    sendError(ctx, 1, "Unsupported notification type", true);
                    break;
            }
        } catch (final Exception ex) {
            logger.error("Error processing VK payment notification", ex);
            sendError(ctx, 2, "Internal server error", false);
        }
    }

    private void handleGetSubscription(final RoutingContext ctx, final Map<String, String> params) {
        final String itemId = resolveItemId(params);
        if (itemId == null) {
            sendError(ctx, 11, "Missing item parameter", true);
            return;
        }

        final Optional<SubscriptionService.SubscriptionPlan> planOpt = subscriptionService.findPlanById(itemId);
        if (planOpt.isEmpty()) {
            sendError(ctx, 20, "Subscription not found", true);
            return;
        }

        final SubscriptionService.SubscriptionPlan plan = planOpt.get();

        final JsonObject response = new JsonObject()
            .put("item_id", plan.getId())
            .put("title", plan.getName())
            .put("price", plan.getPriceInVotes())
            .put("period", plan.getDuration())
            .put("trial_duration", plan.getTrialDuration())
            .put("photo_url", plan.getPhotoUrl() != null ? plan.getPhotoUrl() : DEFAULT_PHOTO_URL)
            .put("description", plan.getDescription())
            .put("expiration", plan.getCacheTtlSeconds() != null ? plan.getCacheTtlSeconds() : 86400);

        sendSuccess(ctx, response);
    }

    private void handleSubscriptionStatusChange(final RoutingContext ctx, final Map<String, String> params) {
        final String status = params.get("status");
        final String subscriptionId = params.get("subscription_id");
        final String userIdStr = params.get("user_id");

        if (status == null || subscriptionId == null || userIdStr == null) {
            sendError(ctx, 11, "Missing required parameters", true);
            return;
        }

        final long vkUserId;
        try {
            vkUserId = Long.parseLong(userIdStr);
        } catch (final NumberFormatException ex) {
            sendError(ctx, 11, "Invalid user id", true);
            return;
        }

        final User user = userService.getOrCreateUser(vkUserId);
        final boolean pendingCancel = isPendingCancel(params.get("pending_cancel"));
        final LocalDateTime nextBillDate = parseEpochSeconds(params.get("next_bill_time"));
        final String cancelReason = params.getOrDefault("cancel_reason", "unknown");
        final Integer itemPrice = parseInteger(params.get("item_price"));
        final String itemId = resolveItemId(params);
        final int appOrderId = generateAppOrderId(subscriptionId, vkUserId);

        switch (status.toLowerCase(Locale.ROOT)) {
            case "chargeable": {
                if (itemId == null) {
                    sendError(ctx, 11, "Missing subscription item", true);
                    return;
                }

                final Optional<SubscriptionService.SubscriptionPlan> planOpt = subscriptionService.findPlanById(itemId);
                if (planOpt.isEmpty()) {
                    sendError(ctx, 20, "Subscription plan not found", true);
                    return;
                }

                try {
                    final Subscription subscription = subscriptionService.processChargeableStatus(
                        user.getId(),
                        planOpt.get(),
                        subscriptionId,
                        nextBillDate,
                        pendingCancel,
                        cancelReason,
                        itemPrice,
                        appOrderId
                    );
                    final JsonObject response = buildSubscriptionResponse(subscriptionId, subscription.getAppOrderId());
                    sendSuccess(ctx, response);
                } catch (final RuntimeException ex) {
                    logger.error("Failed to process chargeable subscription {}", subscriptionId, ex);
                    sendError(ctx, 1, "Unable to process subscription", false);
                }
                break;
            }
            case "active": {
                try {
                    final Subscription subscription = subscriptionService.markSubscriptionActive(
                        subscriptionId,
                        nextBillDate,
                        pendingCancel,
                        cancelReason
                    );
                    notifySubscriptionUpdate(user, subscription, "Подписка активирована");
                    final Integer responseOrderId = subscription.getAppOrderId() != null
                        ? subscription.getAppOrderId()
                        : appOrderId;
                    sendSuccess(ctx, buildSubscriptionResponse(subscriptionId, responseOrderId));
                } catch (final RuntimeException ex) {
                    logger.warn("Subscription {} not found for active status", subscriptionId, ex);
                    sendError(ctx, 20, "Subscription not found", true);
                }
                break;
            }
            case "cancelled": {
                final Optional<Subscription> cancelled = subscriptionService.cancelSubscriptionByVkId(subscriptionId, cancelReason);
                notifySubscriptionUpdate(user, cancelled.orElse(null), "Подписка отменена");
                sendSuccess(ctx, buildSubscriptionResponse(subscriptionId, appOrderId));
                break;
            }
            default:
                logger.warn("Unsupported subscription status: {}", status);
                sendError(ctx, 100, "Unsupported subscription status", true);
                break;
        }
    }

    private String resolveItemId(final Map<String, String> params) {
        final String itemId = params.get("item_id");
        if (itemId != null && !itemId.isBlank()) {
            return itemId;
        }
        final String item = params.get("item");
        return item != null && !item.isBlank() ? item : null;
    }

    private LocalDateTime parseEpochSeconds(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            final long epochSeconds = Long.parseLong(value);
            if (epochSeconds <= 0) {
                return null;
            }
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseInteger(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private boolean isPendingCancel(final String value) {
        if (value == null) {
            return false;
        }
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private int generateAppOrderId(final String subscriptionId, final long vkUserId) {
        final String seed = subscriptionId + ':' + vkUserId;
        return Math.abs(seed.hashCode());
    }

    private JsonObject buildSubscriptionResponse(final String subscriptionId, final Integer appOrderId) {
        final JsonObject response = new JsonObject().put("subscription_id", subscriptionId);
        if (appOrderId != null) {
            response.put("app_order_id", appOrderId);
        }
        return response;
    }

    private void notifySubscriptionUpdate(final User user, final Subscription subscription, final String message) {
        if (user == null || notificationService == null || webSocketHandler == null) {
            return;
        }
        final JsonObject subscriptionJson = subscription != null ? JsonObject.mapFrom(subscription) : null;
        final Map<String, Object> data = new HashMap<>();
        if (subscriptionJson != null) {
            data.put("subscription", subscriptionJson.getMap());
        }
        final Notification notification = notificationService.createNotification(
            user.getId(),
            Notification.NotificationType.SYSTEM,
            "Подписка",
            message,
            data.isEmpty() ? null : data
        );
        webSocketHandler.sendNotificationToUser(user.getId(), JsonObject.mapFrom(notification));
    }

    private Map<String, String> parseFormBody(final String rawBody) {
        final Map<String, String> result = new HashMap<>();
        if (rawBody == null || rawBody.isEmpty()) {
            return result;
        }

        final String[] pairs = rawBody.split("&");
        for (final String pair : pairs) {
            final int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            final String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            final String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private boolean verifySignature(final Map<String, String> params, final String signature) {
        try {
            final Map<String, String> sorted = new TreeMap<>();
            params.forEach(sorted::put);
            sorted.remove("sig");

            final StringBuilder builder = new StringBuilder();
            sorted.forEach((key, value) -> builder.append(key).append('=').append(value));
            builder.append(clientSecret);

            final MessageDigest md = MessageDigest.getInstance("MD5");
            final byte[] digest = md.digest(builder.toString().getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (final byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString().equalsIgnoreCase(signature);
        } catch (final Exception ex) {
            logger.error("Failed to verify signature", ex);
            return false;
        }
    }

    private void sendSuccess(final RoutingContext ctx, final JsonObject payload) {
        final JsonObject response = new JsonObject().put("response", payload);
        sendJson(ctx, response);
    }

    private void sendError(final RoutingContext ctx, final int errorCode, final String message, final boolean critical) {
        final JsonObject error = new JsonObject()
            .put("error", new JsonObject()
                .put("error_code", errorCode)
                .put("error_msg", message)
                .put("critical", critical));
        sendJson(ctx, error);
    }

    private void sendJson(final RoutingContext ctx, final JsonObject json) {
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json; charset=utf-8")
            .end(json.encode());
    }
}
