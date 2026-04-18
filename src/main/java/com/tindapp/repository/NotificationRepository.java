package com.tindapp.repository;

import com.tindapp.model.Notification;

import java.util.List;

public interface NotificationRepository extends Repository<Notification, String> {

    List<Notification> findByUserId(Long userId, int page, int limit);

    void markAsRead(String notificationId);

    void markAllAsReadByUserId(Long userId);

    void markAsReadByIds(List<String> notificationIds);

    long countUnreadByUserId(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
