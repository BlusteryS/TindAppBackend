package com.tindapp.service;

import com.tindapp.model.Subscription;
import com.tindapp.repository.SubscriptionRepository;
import com.tindapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = null; // Will be injected if needed
    }

    public List<SubscriptionPlan> getAvailablePlans() {
        return List.of(
            new SubscriptionPlan("basic_1m", "Базовая подписка", Subscription.SubscriptionType.BASIC,
                199.0, 50, 30, List.of("Безлимитные сообщения", "Приоритет в поиске")),
            new SubscriptionPlan("premium_1m", "Премиум подписка", Subscription.SubscriptionType.PREMIUM,
                399.0, 100, 30, List.of("Все возможности базовой", "Анонимный режим", "Без рекламы", "Статистика профиля"))
        );
    }

    public Optional<Subscription> getActiveSubscription(Long userId) {
        return subscriptionRepository.findActiveByUserId(userId);
    }

    public List<Subscription> getUserSubscriptions(Long userId) {
        return subscriptionRepository.findByUserId(userId);
    }

    public Subscription purchaseSubscription(Long userId, String planId, Subscription.PaymentMethod paymentMethod) {
        Optional<Subscription> existingSubscription = subscriptionRepository.findActiveByUserId(userId);
        if (existingSubscription.isPresent()) {
            throw new RuntimeException("User already has active subscription");
        }

        SubscriptionPlan plan = getAvailablePlans().stream()
            .filter(p -> p.getId().equals(planId))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Subscription plan not found"));

        String subscriptionId = UUID.randomUUID().toString();
        Subscription subscription = new Subscription(subscriptionId, userId, plan.getType(), plan.getPrice(), paymentMethod);

        LocalDateTime endDate = LocalDateTime.now().plusDays(plan.getDuration());
        subscription.setEndDate(endDate);
        subscription.setAutoRenew(true);

        return subscriptionRepository.save(subscription);
    }

    public void cancelSubscription(Long userId) {
        subscriptionRepository.cancelByUserId(userId);
    }

    public boolean hasActiveSubscription(Long userId) {
        return subscriptionRepository.hasActiveSubscription(userId);
    }

    public void processExpiredSubscriptions() {
        List<Subscription> expiring = subscriptionRepository.findExpiring();
        for (Subscription subscription : expiring) {
            if (subscription.getAutoRenew()) {
                renewSubscription(subscription);
            } else {
                subscriptionRepository.expireById(subscription.getId());
            }
        }
    }

    private void renewSubscription(Subscription subscription) {
        subscription.setStartDate(subscription.getEndDate());
        subscription.setEndDate(subscription.getEndDate().plusDays(30));
        subscriptionRepository.save(subscription);
    }

    public long getActiveSubscriptionsCount() {
        return subscriptionRepository.countActiveSubscriptions();
    }

    public static class SubscriptionPlan {
        private final String id;
        private final String name;
        private final Subscription.SubscriptionType type;
        private final Double price;
        private final Integer priceInVotes;
        private final Integer duration;
        private final List<String> features;

        public SubscriptionPlan(String id, String name, Subscription.SubscriptionType type,
                              Double price, Integer priceInVotes, Integer duration, List<String> features) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.price = price;
            this.priceInVotes = priceInVotes;
            this.duration = duration;
            this.features = features;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public Subscription.SubscriptionType getType() { return type; }
        public Double getPrice() { return price; }
        public Integer getPriceInVotes() { return priceInVotes; }
        public Integer getDuration() { return duration; }
        public List<String> getFeatures() { return features; }
        public String getDescription() { return "Подписка на " + duration + " дней"; }
    }
}
