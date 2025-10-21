package com.tindapp.repository;

import com.tindapp.model.Notification;

import java.util.List;

public interface NotificationRepository extends Repository<Notification, String> {

    List<Notification> findByUserId(Long userId);

    List<Notification> findByUserId(Long userId, int page, int limit);

    List<Notification> findUnreadByUserId(Long userId);

    void markAsRead(String notificationId);

    void markAllAsReadByUserId(Long userId);

    void markAsReadByIds(List<String> notificationIds);

    long countUnreadByUserId(Long userId);

    List<Notification> findByType(Notification.NotificationType type);

    void deleteByUserId(Long userId);
}
