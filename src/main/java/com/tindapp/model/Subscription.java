package com.tindapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Subscription {

    private String id;
    private Long userId;
    private SubscriptionType type;
    private SubscriptionStatus status;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Double price;
    private PaymentMethod paymentMethod;
    private Boolean autoRenew;
    private String planId;
    private String vkSubscriptionId;
    private Integer priceInVotes;
    private LocalDateTime nextBillDate;
    private Boolean pendingCancel;
    private String cancelReason;
    private Integer appOrderId;

    public Subscription() {
        status = SubscriptionStatus.ACTIVE;
        autoRenew = false;
        startDate = LocalDateTime.now();
        pendingCancel = false;
    }

    public Subscription(final String id, final Long userId, final SubscriptionType type, final Double price, final PaymentMethod paymentMethod) {
        this();
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.price = price;
        this.paymentMethod = paymentMethod;
    }

    public enum SubscriptionType {
        BASIC, PREMIUM
    }

    public enum SubscriptionStatus {
        ACTIVE, EXPIRED, CANCELLED
    }

    public enum PaymentMethod {
        VK_PAY, CARD, VOTES
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(final Long userId) {
        this.userId = userId;
    }

    public SubscriptionType getType() {
        return type;
    }

    public void setType(final SubscriptionType type) {
        this.type = type;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(final SubscriptionStatus status) {
        this.status = status;
    }

    public LocalDateTime getStartDate() {
        return startDate;
    }

    public void setStartDate(final LocalDateTime startDate) {
        this.startDate = startDate;
    }

    public LocalDateTime getEndDate() {
        return endDate;
    }

    public void setEndDate(final LocalDateTime endDate) {
        this.endDate = endDate;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(final Double price) {
        this.price = price;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(final PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(final String planId) {
        this.planId = planId;
    }

    public String getVkSubscriptionId() {
        return vkSubscriptionId;
    }

    public void setVkSubscriptionId(final String vkSubscriptionId) {
        this.vkSubscriptionId = vkSubscriptionId;
    }

    public Integer getPriceInVotes() {
        return priceInVotes;
    }

    public void setPriceInVotes(final Integer priceInVotes) {
        this.priceInVotes = priceInVotes;
    }

    public LocalDateTime getNextBillDate() {
        return nextBillDate;
    }

    public void setNextBillDate(final LocalDateTime nextBillDate) {
        this.nextBillDate = nextBillDate;
    }

    public Boolean getPendingCancel() {
        return pendingCancel;
    }

    public void setPendingCancel(final Boolean pendingCancel) {
        this.pendingCancel = pendingCancel;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(final String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public Integer getAppOrderId() {
        return appOrderId;
    }

    public void setAppOrderId(final Integer appOrderId) {
        this.appOrderId = appOrderId;
    }

    public Boolean getAutoRenew() {
        return autoRenew;
    }

    public void setAutoRenew(final Boolean autoRenew) {
        this.autoRenew = autoRenew;
    }

    public boolean isActive() {
        final LocalDateTime now = LocalDateTime.now();
        final boolean notExpired = endDate == null || endDate.isAfter(now);
        return status == SubscriptionStatus.ACTIVE && notExpired;
    }

    public void cancel() {
        status = SubscriptionStatus.CANCELLED;
        autoRenew = false;
        pendingCancel = false;
        endDate = LocalDateTime.now();
        nextBillDate = null;
    }

    public void expire() {
        status = SubscriptionStatus.EXPIRED;
        autoRenew = false;
        pendingCancel = false;
    }
}
