package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.FutureUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final double VERIFICATION_THRESHOLD = 0.55;
    private static final double MIN_SKIN_COVERAGE = 0.12;
    private static final double MAX_SKIN_COVERAGE = 0.65;

    private final Vertx vertx;
    private final UserRepository userRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(AppConfig.EXTERNAL_HTTP_CONNECT_TIMEOUT)
        .build();

    public UserService(final Vertx vertx, final UserRepository userRepository) {
        this.vertx = vertx;
        this.userRepository = userRepository;
    }

    public Future<User> getOrCreateUser(final Long vkId) {
        return userRepository.findByVkId(vkId)
            .compose(existingUser -> {
                if (existingUser.isPresent()) {
                    final User user = existingUser.get();
                    user.updateLastSeen();
                    ensureProfileCost(user);
                    ensureRewards(user);
                    applySpecialPrivileges(user);
                    return userRepository.save(user);
                }

                final User newUser = new User(vkId);
                ensureProfileCost(newUser);
                ensureRewards(newUser);
                applySpecialPrivileges(newUser);
                return userRepository.save(newUser);
            });
    }

    public Future<Optional<User>> getUserById(final Long userId) {
        return userRepository.findById(userId);
    }

    public Future<Optional<User>> getUserByVkId(final Long vkId) {
        return userRepository.findByVkId(vkId);
    }

    public Future<User> createUser(final User user) {
        user.setCreatedAtDateTime(LocalDateTime.now());
        user.setUpdatedAtDateTime(LocalDateTime.now());
        ensureProfileCost(user);
        ensureRewards(user);
        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public Future<User> updateUser(final User user) {
        user.setUpdatedAtDateTime(LocalDateTime.now());
        ensureRewards(user);
        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public Future<User> updateProfile(
        final Long userId,
        final String firstName,
        final String lastName,
        final String avatarUrl,
        final String gender,
        final String bio,
        final String country,
        final String city,
        final Integer age,
        final String birthDate,
        final Boolean isVisible,
        final User.UserSettings settings,
        final Integer profileCost,
        final String nativeLanguage
    ) {
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> {
                ensureRewards(user);
                final String previousAvatar = user.getAvatarUrl();

                if (firstName != null) {
                    user.setFirstName(firstName);
                }
                if (lastName != null) {
                    user.setLastName(lastName);
                }
                if (avatarUrl != null) {
                    user.setAvatarUrl(avatarUrl);
                    if (user.isVerified() && (previousAvatar == null || !previousAvatar.equals(avatarUrl))) {
                        user.setWasVerified(true);
                        user.setIsVerified(false);
                    }
                }
                if (gender != null) {
                    user.setGender(gender);
                }
                if (bio != null) {
                    user.setBio(bio);
                }
                if (country != null) {
                    user.setCountry(country);
                }
                if (city != null) {
                    user.setCity(city);
                }

                final LocalDate parsedBirthDate = parseBirthDate(birthDate);
                if (parsedBirthDate != null) {
                    user.setBirthDate(parsedBirthDate);
                    user.setAge(calculateAge(parsedBirthDate));
                } else if (age != null) {
                    user.setAge(age);
                }
                if (isVisible != null) {
                    user.setIsVisible(isVisible);
                }
                if (settings != null) {
                    mergeSettings(user, settings);
                }
                if (profileCost != null) {
                    user.setProfileCost(Math.max(0, profileCost));
                } else {
                    ensureProfileCost(user);
                }
                if (nativeLanguage != null) {
                    user.setNativeLanguage(nativeLanguage);
                }

                applySpecialPrivileges(user);
                return userRepository.save(user);
            });
    }

    public Future<UserVerificationResult> verifyUserWithSelfie(final Long userId, final Path selfiePath) {
        if (selfiePath == null || !Files.exists(selfiePath)) {
            return Future.failedFuture(new IllegalArgumentException("Selfie file not found"));
        }

        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> {
                if (!hasActiveSubscription(user)) {
                    return FutureUtils.failed("Subscription required");
                }
                if (Boolean.TRUE.equals(user.getIsVerified())) {
                    return Future.succeededFuture(new UserVerificationResult(true, 1.0, null));
                }
                if (user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) {
                    return Future.failedFuture(new IllegalArgumentException("Avatar is required for verification"));
                }
                return blocking(() -> verifyUserWithSelfieBlocking(user, selfiePath))
                    .compose(result -> {
                        if (!result.verified()) {
                            return Future.succeededFuture(result);
                        }
                        user.setIsVerified(true);
                        user.setWasVerified(true);
                        return userRepository.save(user).map(saved -> result);
                    });
            });
    }

    public Future<Integer> getUserBalance(final Long userId) {
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .map(User::getBalance);
    }

    public Future<User> purchaseCoins(final Long userId, final Integer amount) {
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> {
                user.setBalance(user.getBalance() + calculateCoinsForPayment(amount));
                return userRepository.save(user);
            });
    }

    public Future<Void> updateUserBalance(final Long userId, final Integer newBalance) {
        return userRepository.updateBalance(userId, newBalance);
    }

    public Future<User> updateCommunityNotifications(final Long userId, final boolean enabled) {
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> {
                if (user.getSettings() == null) {
                    user.setSettings(new User.UserSettings());
                }
                user.getSettings().setAllowCommunityMessages(enabled);
                return userRepository.save(user);
            });
    }

    public Future<User> deductCoins(final Long userId, final Integer amount) {
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> {
                if (user.getBalance() < amount) {
                    return FutureUtils.failed("Insufficient balance");
                }
                user.setBalance(user.getBalance() - amount);
                return userRepository.save(user);
            });
    }

    public Future<Void> updateOnlineStatus(final Long userId, final Boolean isOnline) {
        return userRepository.updateOnlineStatus(userId, Boolean.TRUE.equals(isOnline));
    }

    public Future<Void> refreshOnlineUsers(final Collection<Long> userIds) {
        return userRepository.refreshOnlineUsers(userIds);
    }

    public Future<Void> markStaleOnlineUsersOffline(final Duration ttl) {
        return userRepository.markStaleOnlineUsersOffline(ttl);
    }

    public Future<Void> markAllOffline() {
        return userRepository.markAllOffline();
    }

    public Future<User> banUser(final Long targetUserId, final String reason) {
        return FutureUtils.requirePresent(userRepository.findById(targetUserId), "User not found")
            .compose(user -> {
                user.setIsBanned(true);
                user.setBanReason(reason != null ? reason : "Блокировка администрацией");
                user.setBannedAt(LocalDateTime.now());
                return userRepository.save(user);
            });
    }

    public Future<User> unbanUser(final Long targetUserId) {
        return FutureUtils.requirePresent(userRepository.findById(targetUserId), "User not found")
            .compose(user -> {
                user.setIsBanned(false);
                user.setBanReason(null);
                user.setBannedAt(null);
                return userRepository.save(user);
            });
    }

    public Future<String> mirrorExternalAvatar(final String source) {
        if (source == null || source.trim().isEmpty()) {
            return Future.succeededFuture((String) null);
        }
        final String trimmed = source.trim();
        if (trimmed.contains(AppConfig.UPLOAD_DIR)) {
            return Future.succeededFuture(trimmed);
        }

        final HttpRequest request;
        try {
            request = buildExternalImageRequest(trimmed);
        } catch (final Exception e) {
            logger.warn("Invalid external avatar URL {}", source, e);
            return Future.succeededFuture((String) null);
        }

        return Future.fromCompletionStage(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()))
            .compose(response -> blocking(() -> storeMirroredAvatar(trimmed, response)))
            .otherwise(error -> {
                logger.warn("Failed to mirror external avatar {}", source, error);
                return null;
            });
    }

    public Future<Integer> countOnlineUsers() {
        return userRepository.countOnlineUsers().map(Math::toIntExact);
    }

    public Future<UserStats> getUserStats(final Long userId) {
        return Future.succeededFuture(new UserStats(0, 0, 0, 0, 0, 0));
    }

    public Future<OnlineStats> getOnlineStats() {
        return userRepository.count()
            .compose(totalUsers -> userRepository.countOnlineUsers()
                .map(activeUsers -> new OnlineStats(Math.toIntExact(totalUsers), Math.toIntExact(activeUsers))));
    }

    public Future<RewardStatus> getRewardStatus(final Long userId) {
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .map(user -> {
                ensureRewards(user);
                return buildRewardStatus(user);
            });
    }

    public Future<RewardClaimResult> claimReward(final Long userId, final RewardType type, final boolean confirmed) {
        if (type == null) {
            return FutureUtils.failed("Unknown reward type");
        }
        return FutureUtils.requirePresent(userRepository.findById(userId), "User not found")
            .compose(user -> {
                ensureRewards(user);
                return switch (type) {
                    case AD -> claimAdReward(user, confirmed);
                    case COMMUNITY -> claimCommunityReward(user);
                };
            });
    }

    private Future<RewardClaimResult> claimAdReward(final User user, final boolean confirmed) {
        if (!confirmed) {
            return FutureUtils.failed("Ad was not confirmed");
        }
        user.getRewards().setLastAdRewardAt(LocalDateTime.now());
        user.setBalance((user.getBalance() == null ? 0 : user.getBalance()) + AppConfig.AD_REWARD_AMOUNT);
        return userRepository.save(user)
            .map(saved -> new RewardClaimResult(saved.getBalance(), AppConfig.AD_REWARD_AMOUNT, buildRewardStatus(saved)));
    }

    private Future<RewardClaimResult> claimCommunityReward(final User user) {
        if (Boolean.TRUE.equals(user.getRewards().getSubscriptionBonusClaimed())) {
            return Future.succeededFuture(new RewardClaimResult(user.getBalance(), 0, buildRewardStatus(user)));
        }
        if (user.getVkId() == null) {
            return FutureUtils.failed("VK id is required");
        }
        return isCommunityMember(user.getVkId()).compose(isMember -> {
            if (!isMember) {
                return FutureUtils.failed("Community subscription required");
            }
            user.getRewards().setSubscriptionBonusClaimed(true);
            user.setBalance((user.getBalance() == null ? 0 : user.getBalance()) + AppConfig.SUBSCRIPTION_REWARD_AMOUNT);
            return userRepository.save(user)
                .map(saved -> new RewardClaimResult(saved.getBalance(), AppConfig.SUBSCRIPTION_REWARD_AMOUNT, buildRewardStatus(saved)));
        });
    }

    private void mergeSettings(final User user, final User.UserSettings settings) {
        User.UserSettings currentSettings = user.getSettings();
        if (currentSettings == null) {
            currentSettings = new User.UserSettings();
        }
        if (settings.getShowAge() != null) {
            currentSettings.setShowAge(settings.getShowAge());
        }
        if (settings.getShowCity() != null) {
            currentSettings.setShowCity(settings.getShowCity());
        }
        if (settings.getAllowMessages() != null) {
            currentSettings.setAllowMessages(settings.getAllowMessages());
        }
        if (settings.getAllowCommunityMessages() != null) {
            currentSettings.setAllowCommunityMessages(settings.getAllowCommunityMessages());
        }
        if (settings.getNotifyAnonMessages() != null) {
            currentSettings.setNotifyAnonMessages(settings.getNotifyAnonMessages());
        }
        if (settings.getNotifyAnonDialogClosed() != null) {
            currentSettings.setNotifyAnonDialogClosed(settings.getNotifyAnonDialogClosed());
        }
        if (settings.getNotifyProfileNewChat() != null) {
            currentSettings.setNotifyProfileNewChat(settings.getNotifyProfileNewChat());
        }
        if (settings.getNotifyProfileMessages() != null) {
            currentSettings.setNotifyProfileMessages(settings.getNotifyProfileMessages());
        }
        if (settings.getNotifyProfileDialogClosed() != null) {
            currentSettings.setNotifyProfileDialogClosed(settings.getNotifyProfileDialogClosed());
        }
        if (settings.getNotifySubscriptionProblems() != null) {
            currentSettings.setNotifySubscriptionProblems(settings.getNotifySubscriptionProblems());
        }
        user.setSettings(currentSettings);
    }

    private Future<Boolean> isCommunityMember(final Long vkId) {
        if (vkId == null) {
            return Future.succeededFuture(false);
        }

        final String url = "https://api.vk.com/method/groups.isMember"
            + "?group_id=" + AppConfig.VK_COMMUNITY_GROUP_ID
            + "&user_id=" + URLEncoder.encode(String.valueOf(vkId), java.nio.charset.StandardCharsets.UTF_8)
            + "&extended=0"
            + "&v=" + AppConfig.VK_API_VERSION
            + "&access_token=" + URLEncoder.encode(AppConfig.VK_COMMUNITY_ACCESS_TOKEN, java.nio.charset.StandardCharsets.UTF_8);

        final HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return Future.fromCompletionStage(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .map(response -> {
                final String body = response.body();
                if (body == null || body.isBlank()) {
                    return false;
                }
                final io.vertx.core.json.JsonObject json = new io.vertx.core.json.JsonObject(body);
                if (json.containsKey("error")) {
                    logger.warn("VK groups.isMember error: {}", json.getJsonObject("error"));
                    return false;
                }
                if (json.containsKey("response")) {
                    final Object resp = json.getValue("response");
                    if (resp instanceof Number number) {
                        return number.intValue() == 1;
                    }
                    if (resp instanceof io.vertx.core.json.JsonObject respObj) {
                        return respObj.getInteger("member", 0) == 1;
                    }
                }
                return false;
            })
            .otherwise(error -> {
                logger.warn("Failed to check VK community membership for {}", vkId, error);
                return false;
            });
    }

    private <T> Future<T> blocking(final Callable<T> action) {
        final Promise<T> promise = Promise.promise();
        vertx.executeBlocking(task -> {
            try {
                task.complete(action.call());
            } catch (final Exception e) {
                task.fail(e);
            }
        }, false, promise);
        return promise.future();
    }

    private UserVerificationResult verifyUserWithSelfieBlocking(final User user, final Path selfiePath) {
        try {
            final BufferedImage avatarImage = loadImageFromSource(user.getAvatarUrl());
            final BufferedImage selfieImage = ImageIO.read(selfiePath.toFile());

            if (avatarImage == null || selfieImage == null) {
                throw new IllegalArgumentException("Failed to read images for verification");
            }

            String validationError = validateHumanPresence(avatarImage, true);
            if (validationError != null) {
                return new UserVerificationResult(false, 0.0, validationError);
            }

            validationError = validateHumanPresence(selfieImage, false);
            if (validationError != null) {
                return new UserVerificationResult(false, 0.0, validationError);
            }

            final double similarity = calculateSimilarity(avatarImage, selfieImage);
            if (similarity < VERIFICATION_THRESHOLD) {
                return new UserVerificationResult(false, 0.0, "Вы не похожи на того человека");
            }

            return new UserVerificationResult(true, 1, null);
        } catch (final IllegalArgumentException ex) {
            throw ex;
        } catch (final Exception ex) {
            logger.error("Failed to verify user {} using selfie {}", user.getId(), selfiePath, ex);
            throw new RuntimeException("Unable to verify user at this time");
        }
    }

    private HttpRequest buildExternalImageRequest(final String source) {
        return HttpRequest.newBuilder(URI.create(source))
            .timeout(AppConfig.EXTERNAL_HTTP_REQUEST_TIMEOUT)
            .GET()
            .build();
    }

    private String storeMirroredAvatar(final String source, final HttpResponse<byte[]> response) throws Exception {
        final byte[] body = requireSuccessfulImageBody(source, response);
        final String fileName = "vk-avatar-" + System.currentTimeMillis() + '-' + Math.abs(source.hashCode()) + resolveImageExtension(source, response);
        final Path uploadDir = Paths.get(AppConfig.UPLOAD_DIR);
        Files.createDirectories(uploadDir);
        final Path target = uploadDir.resolve(fileName);
        Files.write(target, body);
        return "/uploads/" + fileName;
    }

    private byte[] requireSuccessfulImageBody(final String source, final HttpResponse<byte[]> response) {
        final int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalArgumentException("Image request failed with status " + statusCode);
        }

        final byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("Image response is empty");
        }
        if (body.length > AppConfig.MAX_UPLOAD_SIZE_BYTES) {
            throw new IllegalArgumentException("Image response is too large: " + body.length);
        }
        return body;
    }

    private String resolveImageExtension(final String source, final HttpResponse<byte[]> response) {
        final String lowerSource = source.toLowerCase();
        if (lowerSource.contains(".png")) {
            return ".png";
        }
        if (lowerSource.contains(".webp")) {
            return ".webp";
        }

        final String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
        if (contentType.contains("png")) {
            return ".png";
        }
        if (contentType.contains("webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    private User.UserRewards ensureRewards(final User user) {
        if (user == null) {
            return null;
        }
        if (user.getRewards() == null) {
            user.setRewards(new User.UserRewards());
        }
        return user.getRewards();
    }

    private Integer calculateAge(final LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        final LocalDate today = LocalDate.now();
        int age = today.getYear() - birthDate.getYear();
        if (birthDate.plusYears(age).isAfter(today)) {
            age -= 1;
        }
        return Math.max(age, 0);
    }

    private LocalDate parseBirthDate(final String birthDate) {
        if (birthDate == null || birthDate.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDate.parse(birthDate.trim());
        } catch (final Exception ex) {
            logger.warn("Invalid birthDate provided: {}", birthDate);
            return null;
        }
    }

    private BufferedImage loadImageFromSource(final String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }

        try {
            if (source.startsWith("http://") || source.startsWith("https://")) {
                final HttpResponse<byte[]> response = httpClient.send(
                    buildExternalImageRequest(source.trim()),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                return ImageIO.read(new ByteArrayInputStream(requireSuccessfulImageBody(source, response)));
            }

            final String normalized = source.startsWith("/") ? source.substring(1) : source;
            final List<Path> candidates = new ArrayList<>();
            candidates.add(Paths.get(normalized));
            candidates.add(Paths.get(AppConfig.UPLOAD_DIR, normalized));

            if (normalized.startsWith("uploads/")) {
                final String withoutPrefix = normalized.substring("uploads/".length());
                candidates.add(Paths.get(AppConfig.UPLOAD_DIR, withoutPrefix));
            }

            final Path fileName = Paths.get(normalized).getFileName();
            if (fileName != null) {
                candidates.add(Paths.get(AppConfig.UPLOAD_DIR, fileName.toString()));
            }

            for (final Path candidate : candidates) {
                if (candidate != null && Files.exists(candidate)) {
                    return ImageIO.read(candidate.toFile());
                }
            }
        } catch (final Exception e) {
            logger.warn("Failed to load image from {}", source, e);
        }

        return null;
    }

    private double calculateSimilarity(final BufferedImage first, final BufferedImage second) {
        final int size = 64;
        final BufferedImage normalizedFirst = resizeImage(first, size, size);
        final BufferedImage normalizedSecond = resizeImage(second, size, size);

        if (isTooBlurry(normalizedSecond)) {
            throw new IllegalArgumentException("Selfie too blurry for verification");
        }

        final double pixelSimilarity = pixelSimilarity(normalizedFirst, normalizedSecond);
        final double hashSimilarity = dhashSimilarity(normalizedFirst, normalizedSecond);
        final double histogramSimilarity = histogramCosineSimilarity(normalizedFirst, normalizedSecond);
        final double combined = (hashSimilarity * 0.5) + (histogramSimilarity * 0.3) + (pixelSimilarity * 0.2);
        return Math.max(0.0, Math.min(1.0, combined));
    }

    private BufferedImage resizeImage(final BufferedImage source, final int width, final int height) {
        final BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return output;
    }

    private double pixelSimilarity(final BufferedImage first, final BufferedImage second) {
        final int width = first.getWidth();
        final int height = first.getHeight();
        double diff = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int grayA = rgbToGray(first.getRGB(x, y));
                final int grayB = rgbToGray(second.getRGB(x, y));
                diff += Math.abs(grayA - grayB) / 255.0;
            }
        }
        final double similarity = 1.0 - (diff / (width * height));
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private double dhashSimilarity(final BufferedImage first, final BufferedImage second) {
        final long hashA = calculateDHash(first);
        final long hashB = calculateDHash(second);
        final int distance = Long.bitCount(hashA ^ hashB);
        final double similarity = 1.0 - (distance / 64.0);
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private long calculateDHash(final BufferedImage image) {
        final BufferedImage resized = resizeImage(image, 9, 8);
        long hash = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                final int left = rgbToGray(resized.getRGB(x, y));
                final int right = rgbToGray(resized.getRGB(x + 1, y));
                hash = (hash << 1) | (left > right ? 1L : 0L);
            }
        }
        return hash;
    }

    private double histogramCosineSimilarity(final BufferedImage first, final BufferedImage second) {
        final double[] histA = grayscaleHistogram(first);
        final double[] histB = grayscaleHistogram(second);

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < histA.length; i++) {
            dot += histA[i] * histB[i];
            normA += histA[i] * histA[i];
            normB += histB[i] * histB[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        final double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private boolean hasActiveSubscription(final User user) {
        return user != null && user.getSubscription() != null && Boolean.TRUE.equals(user.getSubscription().getIsActive());
    }

    private double[] grayscaleHistogram(final BufferedImage image) {
        final double[] hist = new double[256];
        final int width = image.getWidth();
        final int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                hist[rgbToGray(image.getRGB(x, y))] += 1.0;
            }
        }
        final double total = width * height;
        if (total > 0) {
            for (int i = 0; i < hist.length; i++) {
                hist[i] /= total;
            }
        }
        return hist;
    }

    private boolean isTooBlurry(final BufferedImage image) {
        return laplacianVariance(image) < 35.0;
    }

    private String validateHumanPresence(final BufferedImage image, final boolean isAvatar) {
        final BufferedImage normalized = resizeImage(image, 160, 160);
        final SkinStats stats = calculateSkinStats(normalized);

        if (stats.coverage < MIN_SKIN_COVERAGE || stats.coverage > MAX_SKIN_COVERAGE) {
            return isAvatar ? "Аватар не похож на лицо" : "На селфи не найдено лицо";
        }
        if (stats.boundingBoxCoverage < 0.02) {
            return isAvatar ? "Аватар не похож на лицо" : "На селфи не найдено лицо";
        }
        if (stats.aspectRatio < 0.6 || stats.aspectRatio > 1.8) {
            return isAvatar ? "Аватар имеет некорректные пропорции" : "Лицо имеет некорректные пропорции";
        }
        if (stats.darkOnSkin < 0.01 || stats.darkOnSkin > 0.25) {
            return isAvatar ? "На аватаре не видно черт лица" : "На селфи не видно черт лица";
        }
        if (stats.centerCoverage < 0.3) {
            return isAvatar ? "Аватар не похож на лицо" : "Лицо должно быть ближе к центру кадра";
        }
        if (stats.edgeSkinRatio > 0.35) {
            return isAvatar ? "Аватар не похож на лицо" : "Камера смотрит не на лицо (слишком много кожи по краям)";
        }
        if (isTooBlurry(normalized)) {
            return isAvatar ? "Аватар слишком размытый" : "Селфи слишком размыто";
        }
        if (calculateEntropy(normalized) < 3.0) {
            return isAvatar ? "Аватар слишком однотонный" : "Селфи слишком однотонное";
        }
        if (looksLikeScreen(normalized)) {
            return "Похоже, камера смотрит на экран, а не на человека";
        }
        return null;
    }

    private SkinStats calculateSkinStats(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int total = width * height;
        int skinPixels = 0;
        int darkOnSkin = 0;
        int centerSkin = 0;
        int edgeSkin = 0;

        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        final int centerStartX = (int) (width * 0.25);
        final int centerEndX = (int) (width * 0.75);
        final int centerStartY = (int) (height * 0.25);
        final int centerEndY = (int) (height * 0.75);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int rgb = image.getRGB(x, y);
                final int r = (rgb >> 16) & 0xFF;
                final int g = (rgb >> 8) & 0xFF;
                final int b = rgb & 0xFF;

                final double cb = (-0.168736 * r) + (-0.331264 * g) + (0.5 * b) + 128;
                final double cr = (0.5 * r) + (-0.418688 * g) + (-0.081312 * b) + 128;

                final boolean skin = r > 60 && g > 40 && b > 20
                    && (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 15)
                    && r > g && r > b
                    && cr > 135 && cr < 180
                    && cb > 85 && cb < 135;

                if (skin) {
                    skinPixels++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);

                    final int gray = rgbToGray(rgb);
                    if (gray < 70) {
                        darkOnSkin++;
                    }

                    final boolean inCenter = x >= centerStartX && x <= centerEndX && y >= centerStartY && y <= centerEndY;
                    if (inCenter) {
                        centerSkin++;
                    } else if (x < 8 || x > width - 9 || y < 8 || y > height - 9) {
                        edgeSkin++;
                    }
                }
            }
        }

        final double coverage = total > 0 ? (double) skinPixels / total : 0.0;
        final double darkRatio = skinPixels > 0 ? (double) darkOnSkin / skinPixels : 0.0;
        final double centerCoverage = skinPixels > 0 ? (double) centerSkin / skinPixels : 0.0;
        final double edgeSkinRatio = skinPixels > 0 ? (double) edgeSkin / skinPixels : 0.0;

        double aspectRatio = 0.0;
        double bboxCoverage = 0.0;
        if (maxX >= minX && maxY >= minY) {
            final int bboxW = (maxX - minX) + 1;
            final int bboxH = (maxY - minY) + 1;
            aspectRatio = (double) bboxW / bboxH;
            bboxCoverage = (double) (bboxW * bboxH) / total;
        }

        return new SkinStats(coverage, bboxCoverage, aspectRatio, darkRatio, centerCoverage, edgeSkinRatio);
    }

    private double calculateEntropy(final BufferedImage image) {
        final double[] hist = grayscaleHistogram(image);
        double entropy = 0.0;
        for (final double v : hist) {
            if (v > 0) {
                entropy += -v * (Math.log(v) / Math.log(2));
            }
        }
        return entropy;
    }

    private boolean looksLikeScreen(final BufferedImage image) {
        return pixelGridScore(image) > 22.0 && averageSaturation(image) < 0.25;
    }

    private double pixelGridScore(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        double accum = 0.0;
        int count = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int current = rgbToGray(image.getRGB(x, y));
                if (x + 1 < width && (x & 1) == 0) {
                    accum += Math.abs(current - rgbToGray(image.getRGB(x + 1, y)));
                    count++;
                }
                if (y + 1 < height && (y & 1) == 0) {
                    accum += Math.abs(current - rgbToGray(image.getRGB(x, y + 1)));
                    count++;
                }
            }
        }
        return count > 0 ? accum / count : 0.0;
    }

    private double averageSaturation(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        double total = 0.0;
        int count = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int rgb = image.getRGB(x, y);
                final int r = (rgb >> 16) & 0xFF;
                final int g = (rgb >> 8) & 0xFF;
                final int b = rgb & 0xFF;
                final double max = Math.max(r, Math.max(g, b));
                final double min = Math.min(r, Math.min(g, b));
                total += max == 0 ? 0 : (max - min) / max;
                count++;
            }
        }
        return count > 0 ? total / count : 0.0;
    }

    private double laplacianVariance(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        double sum = 0.0;
        double sumSq = 0.0;
        int count = 0;
        final int[][] kernel = {{0, 1, 0}, {1, -4, 1}, {0, 1, 0}};

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double lap = 0.0;
                for (int ky = -1; ky <= 1; ky++) {
                    for (int kx = -1; kx <= 1; kx++) {
                        lap += rgbToGray(image.getRGB(x + kx, y + ky)) * kernel[ky + 1][kx + 1];
                    }
                }
                sum += lap;
                sumSq += lap * lap;
                count++;
            }
        }
        if (count == 0) {
            return 0.0;
        }
        final double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }

    private int rgbToGray(final int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        return (r + g + b) / 3;
    }

    private void ensureProfileCost(final User user) {
        if (user.getProfileCost() == null || user.getProfileCost() < 0) {
            user.setProfileCost(0);
        }
    }

    private void applySpecialPrivileges(final User user) {
        if (user == null || user.getVkId() == null) {
            return;
        }

        if (AppConfig.ADMIN_VK_IDS.contains(user.getVkId())) {
            user.setIsAdmin(true);
        } else if (user.getIsAdmin() == null) {
            user.setIsAdmin(false);
        }
    }

    private RewardStatus buildRewardStatus(final User user) {
        ensureRewards(user);
        final boolean subscriptionClaimed = user.getRewards() != null
            && Boolean.TRUE.equals(user.getRewards().getSubscriptionBonusClaimed());

        return new RewardStatus(true, null, !subscriptionClaimed, subscriptionClaimed);
    }

    public record UserVerificationResult(boolean verified, double similarity, String reason) {
    }

    public enum RewardType {
        AD,
        COMMUNITY
    }

    public record RewardStatus(boolean adAvailable, Integer adCooldownSeconds, boolean subscriptionAvailable, boolean subscriptionClaimed) {
    }

    public record RewardClaimResult(int balance, int rewardedAmount, RewardStatus rewards) {
    }

    private static int calculateCoinsForPayment(final Integer amount) {
        return switch (amount) {
            case 50 -> 100;
            case 100 -> 200;
            case 250 -> 500;
            case 500 -> 1600;
            case 1500 -> 3000;
            default -> 20;
        };
    }

    public record UserStats(int totalChats, int activeChats, int totalMessages, int likesReceived, int profileViews, int matchesFound) {
    }

    public record OnlineStats(int totalUsers, int activeUsers) {
    }

    private record SkinStats(double coverage, double boundingBoxCoverage, double aspectRatio, double darkOnSkin, double centerCoverage,
                             double edgeSkinRatio) {
    }
}
