package com.tindapp.repository;

import com.tindapp.model.Notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryNotificationRepository implements NotificationRepository {

    private final Map<String, Notification> notifications = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public Notification save(Notification notification) {
        if (notification.getId() == null) {
            notification.setId(String.valueOf(idGenerator.getAndIncrement()));
        }
        notifications.put(notification.getId(), notification);
        return notification;
    }

    @Override
    public Optional<Notification> findById(String id) {
        return Optional.ofNullable(notifications.get(id));
    }

    @Override
    public List<Notification> findAll() {
        return new ArrayList<>(notifications.values());
    }

    @Override
    public List<Notification> findAll(int page, int limit) {
        List<Notification> allNotifications = findAll().stream()
                .sorted((n1, n2) -> n2.getCreatedAt().compareTo(n1.getCreatedAt()))
                .collect(Collectors.toList());

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, allNotifications.size());

        if (start >= allNotifications.size()) {
            return new ArrayList<>();
        }

        return allNotifications.subList(start, end);
    }

    @Override
    public List<Notification> findByUserId(Long userId) {
        return notifications.values().stream()
                .filter(notification -> userId.equals(notification.getUserId()))
                .sorted((n1, n2) -> n2.getCreatedAt().compareTo(n1.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Notification> findByUserId(Long userId, int page, int limit) {
        List<Notification> userNotifications = findByUserId(userId);

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, userNotifications.size());

        if (start >= userNotifications.size()) {
            return new ArrayList<>();
        }

        return userNotifications.subList(start, end);
    }

    @Override
    public List<Notification> findUnreadByUserId(Long userId) {
        return notifications.values().stream()
                .filter(notification -> userId.equals(notification.getUserId()))
                .filter(notification -> Boolean.FALSE.equals(notification.getIsRead()))
                .sorted((n1, n2) -> n2.getCreatedAt().compareTo(n1.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    public void markAsRead(String notificationId) {
        Notification notification = notifications.get(notificationId);
        if (notification != null) {
            notification.markAsRead();
        }
    }

    @Override
    public void markAllAsReadByUserId(Long userId) {
        notifications.values().stream()
                .filter(notification -> userId.equals(notification.getUserId()))
                .forEach(Notification::markAsRead);
    }

    @Override
    public void markAsReadByIds(List<String> notificationIds) {
        notificationIds.forEach(this::markAsRead);
    }

    @Override
    public long countUnreadByUserId(Long userId) {
        return notifications.values().stream()
                .filter(notification -> userId.equals(notification.getUserId()))
                .filter(notification -> Boolean.FALSE.equals(notification.getIsRead()))
                .count();
    }

    @Override
    public List<Notification> findByType(Notification.NotificationType type) {
        return notifications.values().stream()
                .filter(notification -> type.equals(notification.getType()))
                .sorted((n1, n2) -> n2.getCreatedAt().compareTo(n1.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    public void deleteByUserId(Long userId) {
        notifications.entrySet().removeIf(entry ->
            userId.equals(entry.getValue().getUserId()));
    }

    @Override
    public void deleteById(String id) {
        notifications.remove(id);
    }

    @Override
    public boolean existsById(String id) {
        return notifications.containsKey(id);
    }

    @Override
    public long count() {
        return notifications.size();
    }
}
