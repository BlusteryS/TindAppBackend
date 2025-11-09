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
        this.status = SubscriptionStatus.ACTIVE;
        this.autoRenew = false;
        this.startDate = LocalDateTime.now();
        this.pendingCancel = false;
    }

    public Subscription(String id, Long userId, SubscriptionType type, Double price, PaymentMethod paymentMethod) {
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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public SubscriptionType getType() { return type; }
    public void setType(SubscriptionType type) { this.type = type; }

    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }

    public LocalDateTime getStartDate() { return startDate; }
    public void setStartDate(LocalDateTime startDate) { this.startDate = startDate; }

    public LocalDateTime getEndDate() { return endDate; }
    public void setEndDate(LocalDateTime endDate) { this.endDate = endDate; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getVkSubscriptionId() { return vkSubscriptionId; }
    public void setVkSubscriptionId(String vkSubscriptionId) { this.vkSubscriptionId = vkSubscriptionId; }

    public Integer getPriceInVotes() { return priceInVotes; }
    public void setPriceInVotes(Integer priceInVotes) { this.priceInVotes = priceInVotes; }

    public LocalDateTime getNextBillDate() { return nextBillDate; }
    public void setNextBillDate(LocalDateTime nextBillDate) { this.nextBillDate = nextBillDate; }

    public Boolean getPendingCancel() { return pendingCancel; }
    public void setPendingCancel(Boolean pendingCancel) { this.pendingCancel = pendingCancel; }

    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }

    public Integer getAppOrderId() { return appOrderId; }
    public void setAppOrderId(Integer appOrderId) { this.appOrderId = appOrderId; }

    public Boolean getAutoRenew() { return autoRenew; }
    public void setAutoRenew(Boolean autoRenew) { this.autoRenew = autoRenew; }

    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        boolean notExpired = endDate == null || endDate.isAfter(now);
        return status == SubscriptionStatus.ACTIVE && notExpired;
    }

    public void cancel() {
        this.status = SubscriptionStatus.CANCELLED;
        this.autoRenew = false;
        this.pendingCancel = false;
        this.endDate = LocalDateTime.now();
        this.nextBillDate = null;
    }

    public void expire() {
        this.status = SubscriptionStatus.EXPIRED;
        this.autoRenew = false;
        this.pendingCancel = false;
    }
}
