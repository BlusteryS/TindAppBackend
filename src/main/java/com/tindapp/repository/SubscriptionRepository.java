package com.tindapp.repository;

import com.tindapp.model.Subscription;
import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends Repository<Subscription, String> {

    Future<Optional<Subscription>> findActiveByUserId(Long userId);

    Future<List<Subscription>> findExpiring();

    Future<Void> cancelByUserId(Long userId);

    Future<Void> expireById(String subscriptionId);

    Future<Boolean> hasActiveSubscription(Long userId);

    Future<Optional<Subscription>> findByVkSubscriptionId(String vkSubscriptionId);

    Future<Void> cancelByVkSubscriptionId(String vkSubscriptionId);

    Future<Long> countActiveSubscriptions();
}
