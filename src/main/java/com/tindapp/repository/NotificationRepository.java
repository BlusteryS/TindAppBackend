package com.tindapp.repository;

import com.tindapp.model.Notification;
import io.vertx.core.Future;

import java.util.List;

public interface NotificationRepository extends Repository<Notification, String> {

    Future<List<Notification>> findByUserId(Long userId, int page, int limit);

    Future<Void> markAsRead(String notificationId);

    Future<Void> markAllAsReadByUserId(Long userId);

    Future<Void> markAsReadByIds(List<String> notificationIds);

    Future<Long> countUnreadByUserId(Long userId);

    Future<Long> countByUserId(Long userId);

    Future<Void> deleteByUserId(Long userId);
}
