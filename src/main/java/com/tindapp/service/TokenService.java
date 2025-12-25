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

    public TokenService(final UserService userService) {
        this.userService = userService;
        final String rawSecret = System.getenv().getOrDefault("TOKEN_SECRET", AppConfig.TOKEN_SECRET);
        secret = rawSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String createToken(final User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User or user ID is null");
        }

        final long expiresAt = Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS).getEpochSecond();
        final String nonce = UUID.randomUUID().toString().replace("-", "");
        final String payload = user.getId() + ":" + expiresAt + ':' + nonce;
        final String signature = sign(payload);

        return BASE64_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + '.' + signature;
    }

    public User validateToken(final String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }

        try {
            final String[] parts = token.split("\\.");
            if (parts.length != 2) {
                return null;
            }

            final String payload = new String(BASE64_DECODER.decode(parts[0]), StandardCharsets.UTF_8);
            final String providedSignature = parts[1];
            final String expectedSignature = sign(payload);

            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), providedSignature.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }

            final String[] payloadParts = payload.split(":");
            if (payloadParts.length != 3) {
                return null;
            }

            final long userId = Long.parseLong(payloadParts[0]);
            final long expiresAt = Long.parseLong(payloadParts[1]);

            if (Instant.now().getEpochSecond() > expiresAt) {
                return null;
            }

            return userService.getUserById(userId).orElse(null);
        } catch (final Exception e) {
            logger.warn("Token validation error", e);
            return null;
        }
    }

    private String sign(final String payload) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            final byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to sign token", e);
        }
    }

    private String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
