package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

public class TokenService {

    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);

    private static final int TOKEN_EXPIRY_HOURS = 24;
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final UserService userService;
    private final byte[] secret;

    public TokenService(UserService userService) {
        this.userService = userService;
        String rawSecret = System.getenv().getOrDefault("TOKEN_SECRET", AppConfig.TOKEN_SECRET);
        this.secret = rawSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String createToken(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User or user ID is null");
        }

        long expiresAt = Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS).getEpochSecond();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = user.getId() + ":" + expiresAt + ":" + nonce;
        String signature = sign(payload);

        return BASE64_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + signature;
    }

    public User validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }

        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                return null;
            }

            String payload = new String(BASE64_DECODER.decode(parts[0]), StandardCharsets.UTF_8);
            String providedSignature = parts[1];
            String expectedSignature = sign(payload);

            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), providedSignature.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }

            String[] payloadParts = payload.split(":");
            if (payloadParts.length != 3) {
                return null;
            }

            long userId = Long.parseLong(payloadParts[0]);
            long expiresAt = Long.parseLong(payloadParts[1]);

            if (Instant.now().getEpochSecond() > expiresAt) {
                return null;
            }

            return userService.getUserById(userId).orElse(null);
        } catch (Exception e) {
            logger.warn("Token validation error", e);
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign token", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
