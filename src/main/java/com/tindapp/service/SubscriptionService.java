package com.tindapp.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tindapp.model.Subscription;
import com.tindapp.model.User;
import com.tindapp.repository.SubscriptionRepository;
import com.tindapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);
    private static final String DEFAULT_PHOTO_URL = System.getenv("SUBSCRIPTION_PHOTO_URL");

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final List<SubscriptionPlan> availablePlans;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, UserRepository userRepository,
                               NotificationService notificationService) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.availablePlans = Collections.unmodifiableList(buildDefaultPlans());
    }

    private List<SubscriptionPlan> buildDefaultPlans() {
        List<String> premiumFeatures = List.of(
            "Все возможности базовой",
            "Анонимный режим",
            "Без рекламы",
            "Статистика профиля"
        );

        List<SubscriptionPlan> plans = new ArrayList<>();

        plans.add(new SubscriptionPlan(
            "premium_month",
            "Премиум подписка (1 месяц)",
            Subscription.SubscriptionType.PREMIUM,
            399.0,
            100,
            30,
            premiumFeatures,
            "Премиум на 30 дней",
            "month",
            0,
            3600,
            DEFAULT_PHOTO_URL
        ));

        plans.add(new SubscriptionPlan(
            "premium_6months",
            "Премиум подписка (6 месяцев)",
            Subscription.SubscriptionType.PREMIUM,
            2090.0,
            504,
            180,
            premiumFeatures,
            "Премиум на 6 месяцев",
            "6months",
            0,
            3600,
            DEFAULT_PHOTO_URL
        ));

        plans.add(new SubscriptionPlan(
            "premium_year",
            "Премиум подписка (12 месяцев)",
            Subscription.SubscriptionType.PREMIUM,
            3590.0,
            900,
            365,
            premiumFeatures,
            "Премиум на 12 месяцев",
            "year",
            0,
            3600,
            DEFAULT_PHOTO_URL
        ));

        return plans;
    }

    public List<SubscriptionPlan> getAvailablePlans() {
        return availablePlans;
    }

    public Optional<SubscriptionPlan> findPlanById(String planId) {
        if (planId == null) {
            return Optional.empty();
        }
        return availablePlans.stream()
            .filter(plan -> plan.getId().equalsIgnoreCase(planId))
            .findFirst();
    }

    public Optional<SubscriptionPlan> findPlanByTypeAndPeriod(Subscription.SubscriptionType type, String periodCode) {
        return availablePlans.stream()
            .filter(plan -> plan.hasType(type))
            .filter(plan -> plan.getPeriodCode().equalsIgnoreCase(periodCode))
            .findFirst();
    }

    public Optional<Subscription> getActiveSubscription(Long userId) {
        return subscriptionRepository.findActiveByUserId(userId);
    }

    public List<Subscription> getUserSubscriptions(Long userId) {
        return subscriptionRepository.findByUserId(userId);
    }

    public Optional<Subscription> findByVkSubscriptionId(String vkSubscriptionId) {
        return subscriptionRepository.findByVkSubscriptionId(vkSubscriptionId);
    }

    public Subscription purchaseSubscription(Long userId, String planId, Subscription.PaymentMethod paymentMethod) {
        Optional<Subscription> existingSubscription = subscriptionRepository.findActiveByUserId(userId);
        if (existingSubscription.isPresent()) {
            throw new RuntimeException("User already has active subscription");
        }

        SubscriptionPlan plan = findPlanById(planId)
            .orElseThrow(() -> new RuntimeException("Subscription plan not found"));

        String subscriptionId = UUID.randomUUID().toString();
        Subscription subscription = new Subscription(subscriptionId, userId, plan.getPlanType(), plan.getPrice(), paymentMethod);
        subscription.setPlanId(plan.getId());
        subscription.setPriceInVotes(plan.getPriceInVotes());
        subscription.setEndDate(LocalDateTime.now().plusDays(plan.getDuration()));
        subscription.setNextBillDate(subscription.getEndDate());
        subscription.setAutoRenew(true);

        Subscription saved = subscriptionRepository.save(subscription);
        updateUserSubscriptionState(userId, saved);
        return saved;
    }

    public Subscription processChargeableStatus(
        Long userId,
        SubscriptionPlan plan,
        String vkSubscriptionId,
        LocalDateTime nextBillDate,
        boolean pendingCancel,
        String cancelReason,
        Integer priceInVotes,
        Integer appOrderId
    ) {
        Subscription subscription = subscriptionRepository.findByVkSubscriptionId(vkSubscriptionId)
            .orElseGet(() -> new Subscription(vkSubscriptionId, userId, plan.getPlanType(), plan.getPrice(), Subscription.PaymentMethod.VOTES));

        subscription.setUserId(userId);
        subscription.setPlanId(plan.getId());
        subscription.setVkSubscriptionId(vkSubscriptionId);
        subscription.setPaymentMethod(Subscription.PaymentMethod.VOTES);
        subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscription.setStartDate(LocalDateTime.now());
        subscription.setEndDate(LocalDateTime.now().plusDays(plan.getDuration()));
        subscription.setNextBillDate(nextBillDate != null ? nextBillDate : subscription.getEndDate());
        subscription.setAutoRenew(!pendingCancel);
        subscription.setPendingCancel(pendingCancel);
        subscription.setCancelReason(cancelReason);
        subscription.setPrice(plan.getPrice());
        subscription.setPriceInVotes(priceInVotes != null ? priceInVotes : plan.getPriceInVotes());
        subscription.setAppOrderId(appOrderId);

        Subscription saved = subscriptionRepository.save(subscription);
        updateUserSubscriptionState(userId, saved);
        return saved;
    }

    public Subscription markSubscriptionActive(
        String vkSubscriptionId,
        LocalDateTime nextBillDate,
        boolean pendingCancel,
        String cancelReason
    ) {
        Subscription subscription = subscriptionRepository.findByVkSubscriptionId(vkSubscriptionId)
            .orElseThrow(() -> new RuntimeException("Subscription not found"));

        subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscription.setPendingCancel(pendingCancel);
        subscription.setAutoRenew(!pendingCancel);
        subscription.setCancelReason(cancelReason);

        if (nextBillDate != null) {
            subscription.setNextBillDate(nextBillDate);
            if (subscription.getEndDate() == null || subscription.getEndDate().isBefore(nextBillDate)) {
                subscription.setEndDate(nextBillDate);
            }
        }

        Subscription saved = subscriptionRepository.save(subscription);
        updateUserSubscriptionState(saved.getUserId(), saved);
        return saved;
    }

    public void cancelSubscriptionByVkId(String vkSubscriptionId, String cancelReason) {
        subscriptionRepository.findByVkSubscriptionId(vkSubscriptionId).ifPresent(subscription -> {
            subscription.setCancelReason(cancelReason);
            subscription.cancel();
            subscriptionRepository.save(subscription);
            updateUserSubscriptionState(subscription.getUserId(), subscription);
        });
    }

    public void cancelSubscription(Long userId) {
        subscriptionRepository.findActiveByUserId(userId).ifPresent(subscription -> {
            subscription.setCancelReason("app_decision");
            subscription.cancel();
            subscriptionRepository.save(subscription);
            updateUserSubscriptionState(userId, subscription);
        });
    }

    public boolean hasActiveSubscription(Long userId) {
        return subscriptionRepository.hasActiveSubscription(userId);
    }

    public void processExpiredSubscriptions() {
        List<Subscription> expiring = subscriptionRepository.findExpiring();
        for (Subscription subscription : expiring) {
            if (Boolean.TRUE.equals(subscription.getAutoRenew())) {
                renewSubscription(subscription);
            } else {
                subscriptionRepository.expireById(subscription.getId());
                updateUserSubscriptionState(subscription.getUserId(), subscription);
                notificationService.sendSubscriptionExpiryNotification(subscription.getUserId());
            }
        }
    }

    private void renewSubscription(Subscription subscription) {
        Optional<SubscriptionPlan> planOpt = findPlanById(subscription.getPlanId());
        int duration = planOpt.map(SubscriptionPlan::getDuration).orElse(30);
        LocalDateTime newStart = subscription.getEndDate() != null ? subscription.getEndDate() : LocalDateTime.now();
        LocalDateTime newEnd = newStart.plusDays(duration);

        subscription.setStartDate(newStart);
        subscription.setEndDate(newEnd);
        subscription.setNextBillDate(newEnd);

        subscriptionRepository.save(subscription);
        updateUserSubscriptionState(subscription.getUserId(), subscription);
    }

    public long getActiveSubscriptionsCount() {
        return subscriptionRepository.countActiveSubscriptions();
    }

    private void updateUserSubscriptionState(Long userId, Subscription subscription) {
        if (userRepository == null || userId == null) {
            return;
        }

        try {
            userRepository.findById(userId).ifPresent(user -> {
                User.UserSubscription userSubscription = Optional.ofNullable(user.getSubscription())
                    .orElseGet(User.UserSubscription::new);
                userSubscription.setIsActive(subscription.getStatus() == Subscription.SubscriptionStatus.ACTIVE);
                userSubscription.setExpiresAt(subscription.getEndDate());
                if (subscription.getType() != null) {
                    userSubscription.setType(User.SubscriptionType.valueOf(subscription.getType().name()));
                }
                user.setSubscription(userSubscription);
                userRepository.save(user);
            });
        } catch (Exception ex) {
            logger.warn("Failed to update user subscription state for user {}", userId, ex);
        }
    }

    public static class SubscriptionPlan {
        private final String id;
        private final String name;
        @JsonIgnore
        private final Subscription.SubscriptionType subscriptionType;
        private final Double price;
        private final Integer priceInVotes;
        private final Integer duration;
        private final List<String> features;
        private final String description;
        private final String periodCode;
        private final Integer trialDuration;
        private final Integer cacheTtlSeconds;
        private final String photoUrl;

        public SubscriptionPlan(
            String id,
            String name,
            Subscription.SubscriptionType type,
            Double price,
            Integer priceInVotes,
            Integer duration,
            List<String> features,
            String description,
            String periodCode,
            Integer trialDuration,
            Integer cacheTtlSeconds,
            String photoUrl
        ) {
            this.id = id;
            this.name = name;
            this.subscriptionType = type;
            this.price = price;
            this.priceInVotes = priceInVotes;
            this.duration = duration;
            this.features = features;
            this.description = description;
            this.periodCode = periodCode;
            this.trialDuration = trialDuration;
            this.cacheTtlSeconds = cacheTtlSeconds;
            this.photoUrl = photoUrl;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public Subscription.SubscriptionType getPlanType() { return subscriptionType; }
        public boolean hasType(Subscription.SubscriptionType type) { return subscriptionType == type; }
        public Double getPrice() { return price; }
        public Integer getPriceInVotes() { return priceInVotes; }
        public Integer getDuration() { return duration; }
        public List<String> getFeatures() { return features; }
        public String getDescription() { return description; }
        public String getPeriodCode() { return periodCode; }
        public Integer getTrialDuration() { return trialDuration; }
        public Integer getCacheTtlSeconds() { return cacheTtlSeconds; }
        public String getPhotoUrl() { return photoUrl; }
    }
}
