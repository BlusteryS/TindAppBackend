package com.tindapp.service;

import com.tindapp.model.Chat;
import com.tindapp.model.Notification;
import com.tindapp.model.User;
import com.tindapp.repository.NotificationRepository;
import com.tindapp.util.FutureUtils;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final VkGroupNotificationService vkGroupNotificationService;
    private final EventStreamService eventStreamService;

    public NotificationService(final NotificationRepository notificationRepository,
                               final UserService userService,
                               final VkGroupNotificationService vkGroupNotificationService,
                               final EventStreamService eventStreamService) {
        this.notificationRepository = notificationRepository;
        this.userService = userService;
        this.vkGroupNotificationService = vkGroupNotificationService;
        this.eventStreamService = eventStreamService;
    }

    public Future<List<Notification>> getUserNotifications(final Long userId, final int page, final int limit) {
        return notificationRepository.findByUserId(userId, page, limit);
    }

    public Future<Long> getUnreadCount(final Long userId) {
        return notificationRepository.countUnreadByUserId(userId);
    }

    public Future<Integer> countUserNotifications(final Long userId) {
        return notificationRepository.countByUserId(userId).map(Math::toIntExact);
    }

    public Future<Notification> createNotification(final Long userId, final Notification.NotificationType type,
                                                   final String title, final String message) {
        return createNotification(userId, type, title, message, null);
    }

    public Future<Notification> createNotification(final Long userId, final Notification.NotificationType type,
                                                   final String title, final String message,
                                                   final Map<String, Object> data) {
        final Notification notification = new Notification(UUID.randomUUID().toString(), userId, type, title, message);
        if (data != null && !data.isEmpty()) {
            notification.setData(data);
        }
        return notificationRepository.save(notification)
            .onSuccess(saved -> eventStreamService.publishToUser(userId, "notification", toEventJson(saved)));
    }

    private JsonObject toEventJson(final Notification notification) {
        return new JsonObject()
            .put("id", notification.getId())
            .put("userId", notification.getUserId())
            .put("type", notification.getType().name().toLowerCase())
            .put("title", notification.getTitle())
            .put("message", notification.getMessage())
            .put("isRead", notification.getIsRead())
            .put("data", notification.getData())
            .put("createdAt", notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null);
    }

    public Future<Void> markAsRead(final String notificationId) {
        return notificationRepository.markAsRead(notificationId);
    }

    public Future<Void> markNotificationsAsRead(final List<String> notificationIds) {
        return notificationRepository.markAsReadByIds(notificationIds);
    }

    public Future<Void> markAllAsRead(final Long userId) {
        return notificationRepository.markAllAsReadByUserId(userId);
    }

    public Future<Void> deleteNotification(final String notificationId, final Long userId) {
        return notificationRepository.findById(notificationId)
            .compose(notification -> {
                if (notification.isEmpty() || !notification.get().getUserId().equals(userId)) {
                    return FutureUtils.failed("Notification not found or access denied");
                }
                return notificationRepository.deleteById(notificationId);
            });
    }

    public Future<Notification> sendNewMessageNotification(final Long userId, final Chat.ChatType chatType, final String senderName) {
        return getUser(userId).compose(user -> {
            if (user == null) {
                return Future.succeededFuture((Notification) null);
            }

            final boolean enabled = chatType == Chat.ChatType.ANONYMOUS
                ? shouldNotifyAnonMessages(user)
                : shouldNotifyProfileMessages(user);
            if (!enabled) {
                return Future.succeededFuture((Notification) null);
            }

            final String safeSender = senderName != null && !senderName.isBlank() ? senderName : "Собеседник";
            final String title = chatType == Chat.ChatType.ANONYMOUS ? "Сообщение в анонимном чате" : "Новое сообщение";
            final String message = chatType == Chat.ChatType.ANONYMOUS
                ? "У вас новое сообщение в анонимном чате."
                : "Вам написал(а) " + safeSender + " в TindApp.";

            return createNotification(userId, Notification.NotificationType.NEW_MESSAGE, title, message)
                .compose(notification -> sendCommunityNotification(user, title, message).map(v -> notification));
        });
    }

    public Future<Notification> sendProfileChatCreatedNotification(final Long userId, final String initiatorName) {
        return getUser(userId).compose(user -> {
            if (user == null || !shouldNotifyProfileNewChat(user)) {
                return Future.succeededFuture((Notification) null);
            }
            final String safeName = initiatorName != null && !initiatorName.isBlank() ? initiatorName : "Пользователь";
            final String title = "Новый чат";
            final String message = safeName + " начал чат с вами. Ответьте, чтобы продолжить общение.";
            return createNotification(userId, Notification.NotificationType.NEW_MATCH, title, message)
                .compose(notification -> sendCommunityNotification(user, title, message).map(v -> notification));
        });
    }

    public Future<Notification> sendDialogClosedNotification(final Long userId, final Chat.ChatType chatType, final String closedByName) {
        return getUser(userId).compose(user -> {
            if (user == null) {
                return Future.succeededFuture((Notification) null);
            }
            final boolean enabled = chatType == Chat.ChatType.ANONYMOUS
                ? shouldNotifyAnonDialogClosed(user)
                : shouldNotifyProfileDialogClosed(user);
            if (!enabled) {
                return Future.succeededFuture((Notification) null);
            }

            final String safeName = closedByName != null && !closedByName.isBlank() ? closedByName : "Собеседник";
            final String title = "Диалог завершен";
            final String message = chatType == Chat.ChatType.ANONYMOUS
                ? "Собеседник завершил анонимный чат."
                : safeName + " завершил чат.";

            return createNotification(userId, Notification.NotificationType.SYSTEM, title, message)
                .compose(notification -> sendCommunityNotification(user, title, message).map(v -> notification));
        });
    }

    public Future<Notification> sendMatchFoundNotification(final Long userId, final String companionNickname) {
        return getUser(userId).compose(user -> {
            if (user == null) {
                return Future.succeededFuture((Notification) null);
            }
            final String safeName = companionNickname != null && !companionNickname.isBlank() ? companionNickname : "Собеседник";
            final String title = "Найден собеседник!";
            final String message = "Вы подключены к чату с " + safeName;
            return createNotification(userId, Notification.NotificationType.NEW_MATCH, title, message)
                .compose(notification -> sendCommunityNotification(user, title, "Мы нашли для вас собеседника. Загляните в TindApp!")
                    .map(v -> notification));
        });
    }

    public Future<Notification> sendSubscriptionExpiryNotification(final Long userId) {
        return getUser(userId).compose(user -> {
            if (user == null || !shouldNotifySubscriptionProblems(user)) {
                return Future.succeededFuture((Notification) null);
            }
            final String title = "Продлите подписку";
            final String message = "Подписка закончилась, автопродление отключено. Продлите её, чтобы сохранить преимущества.";
            return createNotification(userId, Notification.NotificationType.SUBSCRIPTION_EXPIRY, title, message)
                .compose(notification -> sendCommunityNotification(
                    user,
                    title,
                    "Подписка TindApp закончилась, потому что автопродление отключено. Возобновите её, чтобы не потерять преимущества."
                ).map(v -> notification));
        });
    }

    public Future<Notification> sendSystemNotification(final Long userId, final String title, final String message) {
        return createNotification(userId, Notification.NotificationType.SYSTEM, title, message)
            .compose(notification -> sendCommunityNotification(userId, title, message).map(v -> notification));
    }

    public Future<Boolean> sendCommunityTestNotification(final Long userId) {
        return sendCommunityNotification(
            userId,
            "Уведомления включены",
            "Теперь мы будем присылать сообщения от имени сообщества TindApp при новых событиях."
        ).map(result -> result == VkGroupNotificationService.VkSendResult.SUCCESS);
    }

    private Future<VkGroupNotificationService.VkSendResult> sendCommunityNotification(final Long userId, final String title, final String message) {
        if (vkGroupNotificationService == null) {
            return Future.succeededFuture(VkGroupNotificationService.VkSendResult.FAILED);
        }
        return getUser(userId).compose(user -> user == null
            ? Future.succeededFuture(VkGroupNotificationService.VkSendResult.FAILED)
            : sendCommunityNotification(user, title, message));
    }

    private Future<VkGroupNotificationService.VkSendResult> sendCommunityNotification(final User user, final String title, final String message) {
        if (user == null || user.getVkId() == null || !isCommunityNotificationsEnabled(user) || vkGroupNotificationService == null) {
            return Future.succeededFuture(VkGroupNotificationService.VkSendResult.FAILED);
        }

        return vkGroupNotificationService.sendMessage(user.getVkId(), buildCommunityMessage(title, message))
            .compose(result -> {
                if (result != VkGroupNotificationService.VkSendResult.PERMISSION_ERROR) {
                    return Future.succeededFuture(result);
                }
                return disableCommunityNotifications(user).map(v -> result);
            });
    }

    private boolean isCommunityNotificationsEnabled(final User user) {
        return user.getSettings() != null && Boolean.TRUE.equals(user.getSettings().getAllowCommunityMessages());
    }

    private boolean shouldNotifyAnonMessages(final User user) {
        return getSetting(user, User.UserSettings::getNotifyAnonMessages, true);
    }

    private boolean shouldNotifyAnonDialogClosed(final User user) {
        return getSetting(user, User.UserSettings::getNotifyAnonDialogClosed, true);
    }

    private boolean shouldNotifyProfileNewChat(final User user) {
        return getSetting(user, User.UserSettings::getNotifyProfileNewChat, true);
    }

    private boolean shouldNotifyProfileMessages(final User user) {
        return getSetting(user, User.UserSettings::getNotifyProfileMessages, true);
    }

    private boolean shouldNotifyProfileDialogClosed(final User user) {
        return getSetting(user, User.UserSettings::getNotifyProfileDialogClosed, true);
    }

    private boolean shouldNotifySubscriptionProblems(final User user) {
        return getSetting(user, User.UserSettings::getNotifySubscriptionProblems, true);
    }

    private boolean getSetting(final User user, final java.util.function.Function<User.UserSettings, Boolean> getter, final boolean defaultValue) {
        final User.UserSettings settings = user.getSettings();
        if (settings == null) {
            return defaultValue;
        }
        final Boolean value = getter.apply(settings);
        return value == null ? defaultValue : value;
    }

    private Future<User> getUser(final Long userId) {
        if (userId == null) {
            return Future.succeededFuture((User) null);
        }
        return userService.getUserById(userId).map(optional -> optional.orElse(null));
    }

    private String buildCommunityMessage(final String title, final String body) {
        final StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title.trim()).append("\n\n");
        }
        if (body != null) {
            sb.append(body.trim());
        }
        sb.append("\n\n— TindApp");
        return sb.toString();
    }

    private Future<Void> disableCommunityNotifications(final User user) {
        if (user == null || user.getId() == null) {
            return Future.succeededFuture();
        }
        logger.info("Disabling VK community notifications for user {}", user.getId());
        return userService.updateCommunityNotifications(user.getId(), false).mapEmpty();
    }
}
