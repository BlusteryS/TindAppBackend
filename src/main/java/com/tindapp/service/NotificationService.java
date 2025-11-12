package com.tindapp.service;

import com.tindapp.model.Chat;
import com.tindapp.model.Notification;
import com.tindapp.model.User;
import com.tindapp.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final VkGroupNotificationService vkGroupNotificationService;

    public NotificationService(NotificationRepository notificationRepository,
                               UserService userService,
                               VkGroupNotificationService vkGroupNotificationService) {
        this.notificationRepository = notificationRepository;
        this.userService = userService;
        this.vkGroupNotificationService = vkGroupNotificationService;
    }

    public List<Notification> getUserNotifications(Long userId, int page, int limit) {
        return notificationRepository.findByUserId(userId, page, limit);
    }

    public List<Notification> getUnreadNotifications(Long userId) {
        return notificationRepository.findUnreadByUserId(userId);
    }

    public long getUnreadCount(Long userId) {
        return notificationRepository.countUnreadByUserId(userId);
    }

    public Notification createNotification(Long userId, Notification.NotificationType type,
                                         String title, String message) {
        String notificationId = UUID.randomUUID().toString();
        Notification notification = new Notification(notificationId, userId, type, title, message);
        return notificationRepository.save(notification);
    }

    public void markAsRead(String notificationId) {
        notificationRepository.markAsRead(notificationId);
    }

    public void markNotificationsAsRead(List<String> notificationIds) {
        notificationRepository.markAsReadByIds(notificationIds);
    }

    public void markAllAsRead(Long userId) {
        notificationRepository.markAllAsReadByUserId(userId);
    }

    public void deleteNotification(String notificationId, Long userId) {
        Optional<Notification> notification = notificationRepository.findById(notificationId);
        if (notification.isPresent() && notification.get().getUserId().equals(userId)) {
            notificationRepository.deleteById(notificationId);
        } else {
            throw new RuntimeException("Notification not found or access denied");
        }
    }

    public void sendNewMessageNotification(Long userId, Chat.ChatType chatType, String senderName) {
        User user = getUser(userId);
        if (user == null) {
            return;
        }

        boolean enabled =
            chatType == Chat.ChatType.ANONYMOUS
                ? shouldNotifyAnonMessages(user)
                : shouldNotifyProfileMessages(user);
        if (!enabled) {
            return;
        }

        String safeSender = senderName != null && !senderName.isBlank() ? senderName : "Собеседник";
        String title =
            chatType == Chat.ChatType.ANONYMOUS ? "Сообщение в анонимном чате" : "Новое сообщение";
        String message =
            chatType == Chat.ChatType.ANONYMOUS
                ? "У вас новое сообщение в анонимном чате."
                : "Вам написал(а) " + safeSender + " в TindApp.";

        createNotification(userId, Notification.NotificationType.NEW_MESSAGE, title, message);
        sendCommunityNotification(user, title, message);
    }

    public void sendProfileChatCreatedNotification(Long userId, String initiatorName) {
        User user = getUser(userId);
        if (user == null || !shouldNotifyProfileNewChat(user)) {
            return;
        }

        String safeName = initiatorName != null && !initiatorName.isBlank() ? initiatorName : "Пользователь";
        String title = "Новый чат";
        String message = safeName + " начал чат с вами. Ответьте, чтобы продолжить общение.";

        createNotification(userId, Notification.NotificationType.NEW_MATCH, title, message);
        sendCommunityNotification(user, title, message);
    }

    public void sendDialogClosedNotification(Long userId, Chat.ChatType chatType, String closedByName) {
        User user = getUser(userId);
        if (user == null) {
            return;
        }

        boolean enabled =
            chatType == Chat.ChatType.ANONYMOUS
                ? shouldNotifyAnonDialogClosed(user)
                : shouldNotifyProfileDialogClosed(user);
        if (!enabled) {
            return;
        }

        String safeName = closedByName != null && !closedByName.isBlank() ? closedByName : "Собеседник";
        String title = "Диалог завершен";
        String message =
            chatType == Chat.ChatType.ANONYMOUS
                ? "Собеседник завершил анонимный чат."
                : safeName + " завершил чат.";

        createNotification(userId, Notification.NotificationType.SYSTEM, title, message);
        sendCommunityNotification(user, title, message);
    }

    public void sendMatchFoundNotification(Long userId, String companionNickname) {
        User user = getUser(userId);
        if (user == null) {
            return;
        }

        String safeName = companionNickname != null && !companionNickname.isBlank()
            ? companionNickname
            : "Собеседник";
        String title = "Найден собеседник!";
        String message = "Вы подключены к чату с " + safeName;

        createNotification(userId, Notification.NotificationType.NEW_MATCH, title, message);
        sendCommunityNotification(user, title,
            "Мы нашли для вас собеседника. Загляните в TindApp!");
    }

    public void sendSubscriptionExpiryNotification(Long userId) {
        User user = getUser(userId);
        if (user == null || !shouldNotifySubscriptionProblems(user)) {
            return;
        }

        String title = "Подписка истекает";
        String message = "Ваша подписка истекает через 24 часа";

        createNotification(userId, Notification.NotificationType.SUBSCRIPTION_EXPIRY, title, message);
        sendCommunityNotification(user, title,
            "Подписка TindApp заканчивается через 24 часа. Продлите ее, чтобы не потерять преимущества.");
    }

    public void sendSystemNotification(Long userId, String title, String message) {
        createNotification(
            userId,
            Notification.NotificationType.SYSTEM,
            title,
            message
        );
        sendCommunityNotification(userId, title, message);
    }

    public boolean sendCommunityTestNotification(Long userId) {
        VkGroupNotificationService.VkSendResult result = sendCommunityNotification(
            userId,
            "Уведомления включены",
            "Теперь мы будем присылать сообщения от имени сообщества TindApp при новых событиях."
        );
        return result == VkGroupNotificationService.VkSendResult.SUCCESS;
    }

    private VkGroupNotificationService.VkSendResult sendCommunityNotification(Long userId, String title, String message) {
        if (vkGroupNotificationService == null) {
            return VkGroupNotificationService.VkSendResult.FAILED;
        }

        Optional<User> userOpt = userService.getUserById(userId);
        if (userOpt.isEmpty()) {
            return VkGroupNotificationService.VkSendResult.FAILED;
        }

        return sendCommunityNotification(userOpt.get(), title, message);
    }

    private VkGroupNotificationService.VkSendResult sendCommunityNotification(User user, String title, String message) {
        if (user == null || user.getVkId() == null) {
            return VkGroupNotificationService.VkSendResult.FAILED;
        }

        if (!isCommunityNotificationsEnabled(user)) {
            return VkGroupNotificationService.VkSendResult.FAILED;
        }

        String payload = buildCommunityMessage(title, message);
        VkGroupNotificationService.VkSendResult result =
            vkGroupNotificationService.sendMessage(user.getVkId(), payload);

        if (result == VkGroupNotificationService.VkSendResult.PERMISSION_ERROR) {
            disableCommunityNotifications(user);
        }

        return result;
    }

    private boolean isCommunityNotificationsEnabled(User user) {
        if (user.getSettings() == null) {
            return false;
        }
        return Boolean.TRUE.equals(user.getSettings().getAllowCommunityMessages());
    }

    private boolean shouldNotifyAnonMessages(User user) {
        return getSetting(user, User.UserSettings::getNotifyAnonMessages, true);
    }

    private boolean shouldNotifyAnonDialogClosed(User user) {
        return getSetting(user, User.UserSettings::getNotifyAnonDialogClosed, true);
    }

    private boolean shouldNotifyProfileNewChat(User user) {
        return getSetting(user, User.UserSettings::getNotifyProfileNewChat, true);
    }

    private boolean shouldNotifyProfileMessages(User user) {
        return getSetting(user, User.UserSettings::getNotifyProfileMessages, true);
    }

    private boolean shouldNotifyProfileDialogClosed(User user) {
        return getSetting(user, User.UserSettings::getNotifyProfileDialogClosed, true);
    }

    private boolean shouldNotifySubscriptionProblems(User user) {
        return getSetting(user, User.UserSettings::getNotifySubscriptionProblems, true);
    }

    private boolean getSetting(User user, java.util.function.Function<User.UserSettings, Boolean> getter, boolean defaultValue) {
        User.UserSettings settings = user.getSettings();
        if (settings == null) {
            return defaultValue;
        }
        Boolean value = getter.apply(settings);
        return value == null ? defaultValue : value;
    }

    private User getUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return userService.getUserById(userId).orElse(null);
    }

    private String buildCommunityMessage(String title, String body) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title.trim()).append("\n\n");
        }
        if (body != null) {
            sb.append(body.trim());
        }
        sb.append("\n\n— TindApp");
        return sb.toString();
    }

    private void disableCommunityNotifications(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        logger.info("Disabling VK community notifications for user {}", user.getId());
        userService.updateCommunityNotifications(user.getId(), false);
    }
}
