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
    public Subscription save(final Subscription subscription) {
        if (subscription.getId() == null) {
            subscription.setId(String.valueOf(idGenerator.getAndIncrement()));
        }
        subscriptions.put(subscription.getId(), subscription);
        return subscription;
    }

    @Override
    public Optional<Subscription> findById(final String id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    @Override
    public List<Subscription> findAll(final int page, final int limit) {
        final List<Subscription> allSubs = new ArrayList<>(subscriptions.values()).stream()
            .sorted((s1, s2) -> s2.getStartDate().compareTo(s1.getStartDate()))
            .collect(Collectors.toList());

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, allSubs.size());

        if (start >= allSubs.size()) {
            return new ArrayList<>();
        }

        return allSubs.subList(start, end);
    }

    @Override
    public Optional<Subscription> findActiveByUserId(final Long userId) {
        return subscriptions.values().stream()
            .filter(sub -> userId.equals(sub.getUserId()))
            .filter(Subscription::isActive)
            .findFirst();
    }

    public List<Subscription> findExpiring() {
        final LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);
        return subscriptions.values().stream()
            .filter(sub -> sub.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
            .filter(sub -> sub.getEndDate() != null && sub.getEndDate().isBefore(tomorrow))
            .collect(Collectors.toList());
    }

    @Override
    public void cancelByUserId(final Long userId) {
        subscriptions.values().stream()
            .filter(sub -> userId.equals(sub.getUserId()))
            .filter(sub -> sub.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
            .forEach(Subscription::cancel);
    }

    @Override
    public void expireById(final String subscriptionId) {
        final Subscription subscription = subscriptions.get(subscriptionId);
        if (subscription != null) {
            subscription.expire();
        }
    }

    @Override
    public boolean hasActiveSubscription(final Long userId) {
        return findActiveByUserId(userId).isPresent();
    }

    @Override
    public Optional<Subscription> findByVkSubscriptionId(final String vkSubscriptionId) {
        return subscriptions.values().stream()
            .filter(sub -> vkSubscriptionId != null && vkSubscriptionId.equals(sub.getVkSubscriptionId()))
            .findFirst();
    }

    @Override
    public void cancelByVkSubscriptionId(final String vkSubscriptionId) {
        findByVkSubscriptionId(vkSubscriptionId).ifPresent(Subscription::cancel);
    }

    @Override
    public long countActiveSubscriptions() {
        return subscriptions.values().stream()
            .filter(Subscription::isActive)
            .count();
    }

    @Override
    public void deleteById(final String id) {
        subscriptions.remove(id);
    }

    @Override
    public boolean existsById(final String id) {
        return subscriptions.containsKey(id);
    }

    @Override
    public long count() {
        return subscriptions.size();
    }
}
