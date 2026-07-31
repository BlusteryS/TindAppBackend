package com.tindapp.handler;

import com.tindapp.config.AppConfig;
import com.tindapp.model.Notification;
import com.tindapp.model.Subscription;
import com.tindapp.model.User;
import com.tindapp.service.NotificationService;
import com.tindapp.service.SubscriptionService;
import com.tindapp.service.UserService;
import io.vertx.core.Future;
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
    private final String clientSecret;
    private final SubscriptionService subscriptionService;
    private final UserService userService;
    private final NotificationService notificationService;

    public VkPaymentHandler(final String clientSecret,
                            final SubscriptionService subscriptionService,
                            final UserService userService,
                            final NotificationService notificationService) {
        this.clientSecret = clientSecret;
        this.subscriptionService = subscriptionService;
        this.userService = userService;
        this.notificationService = notificationService;
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
            .put("photo_url", plan.getPhotoUrl() != null ? plan.getPhotoUrl() : AppConfig.SUBSCRIPTION_PHOTO_URL)
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

        final boolean pendingCancel = isPendingCancel(params.get("pending_cancel"));
        final LocalDateTime nextBillDate = parseEpochSeconds(params.get("next_bill_time"));
        final String cancelReason = params.getOrDefault("cancel_reason", "unknown");
        final Integer itemPrice = parseInteger(params.get("item_price"));
        final String itemId = resolveItemId(params);
        final int appOrderId = generateAppOrderId(subscriptionId, vkUserId);
        userService.getOrCreateUser(vkUserId)
            .compose(user -> processSubscriptionStatusChange(
                user,
                status,
                subscriptionId,
                itemId,
                nextBillDate,
                pendingCancel,
                cancelReason,
                itemPrice,
                appOrderId
            ))
            .onSuccess(response -> sendSuccess(ctx, response))
            .onFailure(error -> {
                if (error instanceof PaymentException paymentError) {
                    if (paymentError.logAsError()) {
                        logger.error("VK payment processing failed: {}", paymentError.getMessage(), paymentError);
                    } else {
                        logger.warn("VK payment processing rejected: {}", paymentError.getMessage());
                    }
                    sendError(ctx, paymentError.errorCode(), paymentError.getMessage(), paymentError.critical());
                    return;
                }
                logger.error("Failed to process VK subscription {}", subscriptionId, error);
                sendError(ctx, 2, "Internal server error", false);
            });
    }

    private Future<JsonObject> processSubscriptionStatusChange(final User user,
                                                               final String status,
                                                               final String subscriptionId,
                                                               final String itemId,
                                                               final LocalDateTime nextBillDate,
                                                               final boolean pendingCancel,
                                                               final String cancelReason,
                                                               final Integer itemPrice,
                                                               final int appOrderId) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "chargeable" -> processChargeableStatus(
                user,
                subscriptionId,
                itemId,
                nextBillDate,
                pendingCancel,
                cancelReason,
                itemPrice,
                appOrderId
            );
            case "active" -> subscriptionService.markSubscriptionActive(
                    subscriptionId,
                    nextBillDate,
                    pendingCancel,
                    cancelReason
                )
                .compose(subscription -> notifySubscriptionUpdate(user, subscription, "Подписка активирована")
                    .map(v -> buildSubscriptionResponse(
                        subscriptionId,
                        subscription.getAppOrderId() != null ? subscription.getAppOrderId() : appOrderId
                    )))
                .recover(error -> paymentFailure(20, "Subscription not found", true, false, error));
            case "cancelled" -> subscriptionService.cancelSubscriptionByVkId(subscriptionId, cancelReason)
                .compose(cancelled -> notifySubscriptionUpdate(user, cancelled.orElse(null), "Подписка отменена")
                    .map(v -> buildSubscriptionResponse(subscriptionId, appOrderId)));
            default -> {
                logger.warn("Unsupported subscription status: {}", status);
                yield paymentFailure(100, "Unsupported subscription status", true, false, null);
            }
        };
    }

    private Future<JsonObject> processChargeableStatus(final User user,
                                                       final String subscriptionId,
                                                       final String itemId,
                                                       final LocalDateTime nextBillDate,
                                                       final boolean pendingCancel,
                                                       final String cancelReason,
                                                       final Integer itemPrice,
                                                       final int appOrderId) {
        if (itemId == null) {
            return paymentFailure(11, "Missing subscription item", true, false, null);
        }

        final Optional<SubscriptionService.SubscriptionPlan> planOpt = subscriptionService.findPlanById(itemId);
        if (planOpt.isEmpty()) {
            return paymentFailure(20, "Subscription plan not found", true, false, null);
        }

        return subscriptionService.processChargeableStatus(
                user.getId(),
                planOpt.get(),
                subscriptionId,
                nextBillDate,
                pendingCancel,
                cancelReason,
                itemPrice,
                appOrderId
            )
            .map(subscription -> buildSubscriptionResponse(subscriptionId, subscription.getAppOrderId()))
            .recover(error -> paymentFailure(1, "Unable to process subscription", false, true, error));
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

    private Future<Void> notifySubscriptionUpdate(final User user, final Subscription subscription, final String message) {
        if (user == null || notificationService == null) {
            return Future.succeededFuture();
        }
        final JsonObject subscriptionJson = subscription != null ? JsonObject.mapFrom(subscription) : null;
        final Map<String, Object> data = new HashMap<>();
        if (subscriptionJson != null) {
            data.put("subscription", subscriptionJson.getMap());
        }
        return notificationService.createNotification(
                user.getId(),
                Notification.NotificationType.SYSTEM,
                "Подписка",
                message,
                data.isEmpty() ? null : data
            ).mapEmpty();
    }

    private Future<JsonObject> paymentFailure(final int errorCode,
                                              final String message,
                                              final boolean critical,
                                              final boolean logAsError,
                                              final Throwable cause) {
        return Future.failedFuture(new PaymentException(errorCode, message, critical, logAsError, cause));
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

    private static final class PaymentException extends RuntimeException {
        private final int errorCode;
        private final boolean critical;
        private final boolean logAsError;

        private PaymentException(final int errorCode,
                                 final String message,
                                 final boolean critical,
                                 final boolean logAsError,
                                 final Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
            this.critical = critical;
            this.logAsError = logAsError;
        }

        private int errorCode() {
            return errorCode;
        }

        private boolean critical() {
            return critical;
        }

        private boolean logAsError() {
            return logAsError;
        }
    }
}
