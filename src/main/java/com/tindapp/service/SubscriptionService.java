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

    public SubscriptionService(final SubscriptionRepository subscriptionRepository, final UserRepository userRepository,
                               final NotificationService notificationService) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        availablePlans = Collections.unmodifiableList(buildDefaultPlans());
    }

    private List<SubscriptionPlan> buildDefaultPlans() {
        final List<String> premiumFeatures = List.of(
            "Все возможности базовой",
            "Анонимный режим",
            "Без рекламы",
            "Статистика профиля"
        );

        final List<SubscriptionPlan> plans = new ArrayList<>();

        plans.add(new SubscriptionPlan(
            "premium_month",
            "Премиум подписка (1 месяц)",
            Subscription.SubscriptionType.PREMIUM,
            50.0,
            50,
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
            300.0,
            240,
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
            600.0,
            330,
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

    public Optional<SubscriptionPlan> findPlanById(final String planId) {
        if (planId == null) {
            return Optional.empty();
        }
        return availablePlans.stream()
            .filter(plan -> plan.getId().equalsIgnoreCase(planId))
            .findFirst();
    }

    public Optional<SubscriptionPlan> findPlanByTypeAndPeriod(final Subscription.SubscriptionType type, final String periodCode) {
        return availablePlans.stream()
            .filter(plan -> plan.hasType(type))
            .filter(plan -> plan.getPeriodCode().equalsIgnoreCase(periodCode))
            .findFirst();
    }

    public Optional<Subscription> getActiveSubscription(final Long userId) {
        return subscriptionRepository.findActiveByUserId(userId);
    }

    public List<Subscription> getUserSubscriptions(final Long userId) {
        return subscriptionRepository.findByUserId(userId);
    }

    public Optional<Subscription> findByVkSubscriptionId(final String vkSubscriptionId) {
        return subscriptionRepository.findByVkSubscriptionId(vkSubscriptionId);
    }

    public Subscription purchaseSubscription(final Long userId, final String planId, final Subscription.PaymentMethod paymentMethod) {
        final Optional<Subscription> existingSubscription = subscriptionRepository.findActiveByUserId(userId);
        if (existingSubscription.isPresent()) {
            throw new RuntimeException("User already has active subscription");
        }

        final SubscriptionPlan plan = findPlanById(planId)
            .orElseThrow(() -> new RuntimeException("Subscription plan not found"));

        final String subscriptionId = UUID.randomUUID().toString();
        final Subscription subscription = new Subscription(subscriptionId, userId, plan.getPlanType(), plan.getPrice(), paymentMethod);
        subscription.setPlanId(plan.getId());
        subscription.setPriceInVotes(plan.getPriceInVotes());
        subscription.setEndDate(LocalDateTime.now().plusDays(plan.getDuration()));
        subscription.setNextBillDate(subscription.getEndDate());
        subscription.setAutoRenew(true);

        final Subscription saved = subscriptionRepository.save(subscription);
        updateUserSubscriptionState(userId, saved);
        return saved;
    }

    public Subscription processChargeableStatus(
        final Long userId,
        final SubscriptionPlan plan,
        final String vkSubscriptionId,
        final LocalDateTime nextBillDate,
        final boolean pendingCancel,
        final String cancelReason,
        final Integer priceInVotes,
        final Integer appOrderId
    ) {
        final Subscription subscription = subscriptionRepository.findByVkSubscriptionId(vkSubscriptionId)
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

        final Subscription saved = subscriptionRepository.save(subscription);
        updateUserSubscriptionState(userId, saved);
        return saved;
    }

    public Subscription markSubscriptionActive(
        final String vkSubscriptionId,
        final LocalDateTime nextBillDate,
        final boolean pendingCancel,
        final String cancelReason
    ) {
        final Subscription subscription = subscriptionRepository.findByVkSubscriptionId(vkSubscriptionId)
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

        final Subscription saved = subscriptionRepository.save(subscription);
        updateUserSubscriptionState(saved.getUserId(), saved);
        return saved;
    }

    public void cancelSubscriptionByVkId(final String vkSubscriptionId, final String cancelReason) {
        subscriptionRepository.findByVkSubscriptionId(vkSubscriptionId).ifPresent(subscription -> {
            subscription.setCancelReason(cancelReason);
            subscription.cancel();
            subscriptionRepository.save(subscription);
            updateUserSubscriptionState(subscription.getUserId(), subscription);
        });
    }

    public void cancelSubscription(final Long userId) {
        subscriptionRepository.findActiveByUserId(userId).ifPresent(subscription -> {
            subscription.setCancelReason("app_decision");
            subscription.cancel();
            subscriptionRepository.save(subscription);
            updateUserSubscriptionState(userId, subscription);
        });
    }

    public boolean hasActiveSubscription(final Long userId) {
        return subscriptionRepository.hasActiveSubscription(userId);
    }

    public void processExpiredSubscriptions() {
        final List<Subscription> expiring = subscriptionRepository.findExpiring();
        for (final Subscription subscription : expiring) {
            if (Boolean.TRUE.equals(subscription.getAutoRenew())) {
                renewSubscription(subscription);
            } else {
                subscriptionRepository.expireById(subscription.getId());
                updateUserSubscriptionState(subscription.getUserId(), subscription);
                notificationService.sendSubscriptionExpiryNotification(subscription.getUserId());
            }
        }
    }

    private void renewSubscription(final Subscription subscription) {
        final Optional<SubscriptionPlan> planOpt = findPlanById(subscription.getPlanId());
        final int duration = planOpt.map(SubscriptionPlan::getDuration).orElse(30);
        final LocalDateTime newStart = subscription.getEndDate() != null ? subscription.getEndDate() : LocalDateTime.now();
        final LocalDateTime newEnd = newStart.plusDays(duration);

        subscription.setStartDate(newStart);
        subscription.setEndDate(newEnd);
        subscription.setNextBillDate(newEnd);

        subscriptionRepository.save(subscription);
        updateUserSubscriptionState(subscription.getUserId(), subscription);
    }

    public long getActiveSubscriptionsCount() {
        return subscriptionRepository.countActiveSubscriptions();
    }

    private void updateUserSubscriptionState(final Long userId, final Subscription subscription) {
        if (userRepository == null || userId == null) {
            return;
        }

        try {
            userRepository.findById(userId).ifPresent(user -> {
                final User.UserSubscription userSubscription = Optional.ofNullable(user.getSubscription())
                    .orElseGet(User.UserSubscription::new);
                userSubscription.setIsActive(subscription.getStatus() == Subscription.SubscriptionStatus.ACTIVE);
                userSubscription.setExpiresAt(subscription.getEndDate());
                if (subscription.getType() != null) {
                    userSubscription.setType(User.SubscriptionType.valueOf(subscription.getType().name()));
                }
                user.setSubscription(userSubscription);
                userRepository.save(user);
            });
        } catch (final Exception ex) {
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
            final String id,
            final String name,
            final Subscription.SubscriptionType type,
            final Double price,
            final Integer priceInVotes,
            final Integer duration,
            final List<String> features,
            final String description,
            final String periodCode,
            final Integer trialDuration,
            final Integer cacheTtlSeconds,
            final String photoUrl
        ) {
            this.id = id;
            this.name = name;
            subscriptionType = type;
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

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Subscription.SubscriptionType getPlanType() {
            return subscriptionType;
        }

        public boolean hasType(final Subscription.SubscriptionType type) {
            return subscriptionType == type;
        }

        public Double getPrice() {
            return price;
        }

        public Integer getPriceInVotes() {
            return priceInVotes;
        }

        public Integer getDuration() {
            return duration;
        }

        public List<String> getFeatures() {
            return features;
        }

        public String getDescription() {
            return description;
        }

        public String getPeriodCode() {
            return periodCode;
        }

        public Integer getTrialDuration() {
            return trialDuration;
        }

        public Integer getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public String getPhotoUrl() {
            return photoUrl;
        }
    }
}
