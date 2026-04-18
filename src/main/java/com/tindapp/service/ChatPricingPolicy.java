package com.tindapp.service;

import com.tindapp.config.AppConfig;

public final class ChatPricingPolicy {

    private ChatPricingPolicy() {
    }

    public static int calculateCost(final int onlineUsers) {
        if (onlineUsers < AppConfig.FREE_CHAT_ONLINE_THRESHOLD) {
            return 0;
        }
        return onlineUsers * AppConfig.CHAT_COST_PER_ONLINE_USER;
    }
}
