package com.tindapp.repository;

import com.tindapp.model.Subscription;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends Repository<Subscription, String> {

    Optional<Subscription> findActiveByUserId(Long userId);

    List<Subscription> findByUserId(Long userId);

    List<Subscription> findByStatus(Subscription.SubscriptionStatus status);

    List<Subscription> findByType(Subscription.SubscriptionType type);

    List<Subscription> findExpiring();

    void cancelByUserId(Long userId);

    void expireById(String subscriptionId);

    boolean hasActiveSubscription(Long userId);

    long countActiveSubscriptions();
}
