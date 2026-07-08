package com.tindapp.service;

import com.tindapp.config.AppConfig;

public final class ChatPricingPolicy {

    private ChatPricingPolicy() {
    }

    public static int calculateProfileCost(final int onlineUsers) {
        return calculateScaledCost(onlineUsers);
    }

    public static int calculateAnonymousChatCost(final int searchQueueSize) {
        return calculateScaledCost(searchQueueSize);
    }

    private static int calculateScaledCost(final int audienceSize) {
        final int normalizedSize = Math.max(audienceSize, 0);
        final int rawCost = (normalizedSize * AppConfig.CHAT_COST_PER_ONLINE_USER)
            - (AppConfig.FREE_CHAT_ONLINE_THRESHOLD * AppConfig.CHAT_COST_PER_ONLINE_USER);
        return Math.max(rawCost, 0);
    }
}
