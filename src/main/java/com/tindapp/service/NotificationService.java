package com.tindapp.service;

import com.tindapp.model.Notification;
import com.tindapp.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
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

    public void sendNewMessageNotification(Long userId, String chatId, String senderName) {
        createNotification(
            userId,
            Notification.NotificationType.NEW_MESSAGE,
            "Новое сообщение",
            "У вас новое сообщение от " + senderName
        );
    }

    public void sendMatchFoundNotification(Long userId, String companionNickname) {
        createNotification(
            userId,
            Notification.NotificationType.NEW_MATCH,
            "Найден собеседник!",
            "Вы подключены к чату с " + companionNickname
        );
    }

    public void sendSubscriptionExpiryNotification(Long userId) {
        createNotification(
            userId,
            Notification.NotificationType.SUBSCRIPTION_EXPIRY,
            "Подписка истекает",
            "Ваша подписка истекает через 24 часа"
        );
    }

    public void sendSystemNotification(Long userId, String title, String message) {
        createNotification(
            userId,
            Notification.NotificationType.SYSTEM,
            title,
            message
        );
    }
}
