package com.tindapp.repository;

import com.tindapp.model.Subscription;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemorySubscriptionRepository implements SubscriptionRepository {

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public Subscription save(Subscription subscription) {
        if (subscription.getId() == null) {
            subscription.setId(String.valueOf(idGenerator.getAndIncrement()));
        }
        subscriptions.put(subscription.getId(), subscription);
        return subscription;
    }

    @Override
    public Optional<Subscription> findById(String id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    @Override
    public List<Subscription> findAll() {
        return new ArrayList<>(subscriptions.values());
    }

    @Override
    public List<Subscription> findAll(int page, int limit) {
        List<Subscription> allSubs = findAll().stream()
                .sorted((s1, s2) -> s2.getStartDate().compareTo(s1.getStartDate()))
                .collect(Collectors.toList());

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, allSubs.size());

        if (start >= allSubs.size()) {
            return new ArrayList<>();
        }

        return allSubs.subList(start, end);
    }

    @Override
    public Optional<Subscription> findActiveByUserId(Long userId) {
        return subscriptions.values().stream()
                .filter(sub -> userId.equals(sub.getUserId()))
                .filter(Subscription::isActive)
                .findFirst();
    }

    @Override
    public List<Subscription> findByUserId(Long userId) {
        return subscriptions.values().stream()
                .filter(sub -> userId.equals(sub.getUserId()))
                .sorted((s1, s2) -> s2.getStartDate().compareTo(s1.getStartDate()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Subscription> findByStatus(Subscription.SubscriptionStatus status) {
        return subscriptions.values().stream()
                .filter(sub -> status.equals(sub.getStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Subscription> findByType(Subscription.SubscriptionType type) {
        return subscriptions.values().stream()
                .filter(sub -> type.equals(sub.getType()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Subscription> findExpiring() {
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);
        return subscriptions.values().stream()
                .filter(sub -> sub.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                .filter(sub -> sub.getEndDate() != null && sub.getEndDate().isBefore(tomorrow))
                .collect(Collectors.toList());
    }

    @Override
    public void cancelByUserId(Long userId) {
        subscriptions.values().stream()
                .filter(sub -> userId.equals(sub.getUserId()))
                .filter(sub -> sub.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
                .forEach(Subscription::cancel);
    }

    @Override
    public void expireById(String subscriptionId) {
        Subscription subscription = subscriptions.get(subscriptionId);
        if (subscription != null) {
            subscription.expire();
        }
    }

    @Override
    public boolean hasActiveSubscription(Long userId) {
        return findActiveByUserId(userId).isPresent();
    }

    @Override
    public long countActiveSubscriptions() {
        return subscriptions.values().stream()
                .filter(Subscription::isActive)
                .count();
    }

    @Override
    public void deleteById(String id) {
        subscriptions.remove(id);
    }

    @Override
    public boolean existsById(String id) {
        return subscriptions.containsKey(id);
    }

    @Override
    public long count() {
        return subscriptions.size();
    }
}
