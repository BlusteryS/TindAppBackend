package com.tindapp.service;

import com.tindapp.model.Chat;
import com.tindapp.model.Notification;
import com.tindapp.model.User;
import com.tindapp.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final VkGroupNotificationService vkGroupNotificationService;

    public NotificationService(final NotificationRepository notificationRepository,
                               final UserService userService,
                               final VkGroupNotificationService vkGroupNotificationService) {
        this.notificationRepository = notificationRepository;
        this.userService = userService;
        this.vkGroupNotificationService = vkGroupNotificationService;
    }

    public List<Notification> getUserNotifications(final Long userId, final int page, final int limit) {
        return notificationRepository.findByUserId(userId, page, limit);
    }

    public long getUnreadCount(final Long userId) {
        return notificationRepository.countUnreadByUserId(userId);
    }

    public int countUserNotifications(final Long userId) {
        return Math.toIntExact(notificationRepository.countByUserId(userId));
    }

    public Notification createNotification(final Long userId, final Notification.NotificationType type,
                                           final String title, final String message) {
        return createNotification(userId, type, title, message, null);
    }

    public Notification createNotification(final Long userId, final Notification.NotificationType type,
                                           final String title, final String message,
                                           final Map<String, Object> data) {
        final String notificationId = UUID.randomUUID().toString();
        final Notification notification = new Notification(notificationId, userId, type, title, message);
        if (data != null && !data.isEmpty()) {
            notification.setData(data);
        }
        return notificationRepository.save(notification);
    }

    public void markAsRead(final String notificationId) {
        notificationRepository.markAsRead(notificationId);
    }

    public void markNotificationsAsRead(final List<String> notificationIds) {
        notificationRepository.markAsReadByIds(notificationIds);
    }

    public void markAllAsRead(final Long userId) {
        notificationRepository.markAllAsReadByUserId(userId);
    }

    public void deleteNotification(final String notificationId, final Long userId) {
        final Optional<Notification> notification = notificationRepository.findById(notificationId);
        if (notification.isPresent() && notification.get().getUserId().equals(userId)) {
            notificationRepository.deleteById(notificationId);
        } else {
            throw new RuntimeException("Notification not found or access denied");
        }
    }

    public void sendNewMessageNotification(final Long userId, final Chat.ChatType chatType, final String senderName) {
        final User user = getUser(userId);
        if (user == null) {
            return;
        }

        final boolean enabled =
            chatType == Chat.ChatType.ANONYMOUS
                ? shouldNotifyAnonMessages(user)
                : shouldNotifyProfileMessages(user);
        if (!enabled) {
            return;
        }

        final String safeSender = senderName != null && !senderName.isBlank() ? senderName : "Собеседник";
        final String title =
            chatType == Chat.ChatType.ANONYMOUS ? "Сообщение в анонимном чате" : "Новое сообщение";
        final String message =
            chatType == Chat.ChatType.ANONYMOUS
                ? "У вас новое сообщение в анонимном чате."
                : "Вам написал(а) " + safeSender + " в TindApp.";

        createNotification(userId, Notification.NotificationType.NEW_MESSAGE, title, message);
        sendCommunityNotification(user, title, message);
    }

    public void sendProfileChatCreatedNotification(final Long userId, final String initiatorName) {
        final User user = getUser(userId);
        if (user == null || !shouldNotifyProfileNewChat(user)) {
            return;
        }

        final String safeName = initiatorName != null && !initiatorName.isBlank() ? initiatorName : "Пользователь";
        final String title = "Новый чат";
        final String message = safeName + " начал чат с вами. Ответьте, чтобы продолжить общение.";

        createNotification(userId, Notification.NotificationType.NEW_MATCH, title, message);
        sendCommunityNotification(user, title, message);
    }

    public void sendDialogClosedNotification(final Long userId, final Chat.ChatType chatType, final String closedByName) {
        final User user = getUser(userId);
        if (user == null) {
            return;
        }

        final boolean enabled =
            chatType == Chat.ChatType.ANONYMOUS
                ? shouldNotifyAnonDialogClosed(user)
                : shouldNotifyProfileDialogClosed(user);
        if (!enabled) {
            return;
        }

        final String safeName = closedByName != null && !closedByName.isBlank() ? closedByName : "Собеседник";
        final String title = "Диалог завершен";
        final String message =
            chatType == Chat.ChatType.ANONYMOUS
                ? "Собеседник завершил анонимный чат."
                : safeName + " завершил чат.";

        createNotification(userId, Notification.NotificationType.SYSTEM, title, message);
        sendCommunityNotification(user, title, message);
    }

    public void sendMatchFoundNotification(final Long userId, final String companionNickname) {
        final User user = getUser(userId);
        if (user == null) {
            return;
        }

        final String safeName = companionNickname != null && !companionNickname.isBlank()
            ? companionNickname
            : "Собеседник";
        final String title = "Найден собеседник!";
        final String message = "Вы подключены к чату с " + safeName;

        createNotification(userId, Notification.NotificationType.NEW_MATCH, title, message);
        sendCommunityNotification(user, title,
            "Мы нашли для вас собеседника. Загляните в TindApp!");
    }

    public void sendSubscriptionExpiryNotification(final Long userId) {
        final User user = getUser(userId);
        if (user == null || !shouldNotifySubscriptionProblems(user)) {
            return;
        }

        final String title = "Продлите подписку";
        final String message = "Подписка закончилась, автопродление отключено. Продлите её, чтобы сохранить преимущества.";

        createNotification(userId, Notification.NotificationType.SUBSCRIPTION_EXPIRY, title, message);
        sendCommunityNotification(user, title,
            "Подписка TindApp закончилась, потому что автопродление отключено. Возобновите её, чтобы не потерять преимущества.");
    }

    public void sendSystemNotification(final Long userId, final String title, final String message) {
        createNotification(
            userId,
            Notification.NotificationType.SYSTEM,
            title,
            message
        );
        sendCommunityNotification(userId, title, message);
    }

    public boolean sendCommunityTestNotification(final Long userId) {
        final VkGroupNotificationService.VkSendResult result = sendCommunityNotification(
            userId,
            "Уведомления включены",
            "Теперь мы будем присылать сообщения от имени сообщества TindApp при новых событиях."
        );
        return result == VkGroupNotificationService.VkSendResult.SUCCESS;
    }

    private VkGroupNotificationService.VkSendResult sendCommunityNotification(final Long userId, final String title, final String message) {
        if (vkGroupNotificationService == null) {
            return VkGroupNotificationService.VkSendResult.FAILED;
        }

        final Optional<User> userOpt = userService.getUserById(userId);
        if (userOpt.isEmpty()) {
            return VkGroupNotificationService.VkSendResult.FAILED;
        }

        return sendCommunityNotification(userOpt.get(), title, message);
    }

    private VkGroupNotificationService.VkSendResult sendCommunityNotification(final User user, final String title, final String message) {
        if (user == null || user.getVkId() == null) {
            return VkGroupNotificationService.VkSendResult.FAILED;
        }

        if (!isCommunityNotificationsEnabled(user)) {
            return VkGroupNotificationService.VkSendResult.FAILED;
        }

        final String payload = buildCommunityMessage(title, message);
        final VkGroupNotificationService.VkSendResult result =
            vkGroupNotificationService.sendMessage(user.getVkId(), payload);

        if (result == VkGroupNotificationService.VkSendResult.PERMISSION_ERROR) {
            disableCommunityNotifications(user);
        }

        return result;
    }

    private boolean isCommunityNotificationsEnabled(final User user) {
        if (user.getSettings() == null) {
            return false;
        }
        return Boolean.TRUE.equals(user.getSettings().getAllowCommunityMessages());
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

    private User getUser(final Long userId) {
        if (userId == null) {
            return null;
        }
        return userService.getUserById(userId).orElse(null);
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

    private void disableCommunityNotifications(final User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        logger.info("Disabling VK community notifications for user {}", user.getId());
        userService.updateCommunityNotifications(user.getId(), false);
    }
}
