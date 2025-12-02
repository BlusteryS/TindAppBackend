package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private static final Set<Long> ADMIN_VK_IDS = java.util.Arrays.stream(System.getenv("ADMIN_VK_IDS").split(","))
        .map(Long::parseLong)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final double VERIFICATION_THRESHOLD = 0.55;
    private static final double MIN_SKIN_COVERAGE = 0.12;
    private static final double MAX_SKIN_COVERAGE = 0.65;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createOrUpdateUser(Long vkId) {
        Optional<User> existingUser = userRepository.findByVkId(vkId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.updateLastSeen();
            ensureProfileCost(user);
            ensureRewards(user);
            applySpecialPrivileges(user);
            return userRepository.save(user);
        } else {
            User newUser = new User(vkId);
            newUser.setBalance(AppConfig.INITIAL_USER_BALANCE); // начальный баланс
            ensureProfileCost(newUser);
            ensureRewards(newUser);
            applySpecialPrivileges(newUser);
            return userRepository.save(newUser);
        }
    }

    public User getOrCreateUser(Long vkId) {
        Optional<User> existingUser = userRepository.findByVkId(vkId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.updateLastSeen();
            ensureProfileCost(user);
            ensureRewards(user);
            applySpecialPrivileges(user);
            return userRepository.save(user);
        } else {
            User newUser = new User(vkId);
            ensureProfileCost(newUser);
            ensureRewards(newUser);
            applySpecialPrivileges(newUser);
            return userRepository.save(newUser);
        }
    }

    public Optional<User> getUserById(Long userId) {
        return userRepository.findById(userId);
    }

    public Optional<User> getUserByVkId(Long vkId) {
        return userRepository.findByVkId(vkId);
    }

    public User createUser(User user) {
        user.setCreatedAtDateTime(LocalDateTime.now());
        user.setUpdatedAtDateTime(LocalDateTime.now());
        ensureProfileCost(user);
        ensureRewards(user);
        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public User updateUser(User user) {
        user.setUpdatedAtDateTime(LocalDateTime.now());
        ensureRewards(user);
        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public User updateProfile(
        Long userId,
        String firstName,
        String lastName,
        String avatarUrl,
        String gender,
        String bio,
        String country,
        String city,
        Integer age,
        String birthDate,
        Boolean isVisible,
        User.UserSettings settings,
        Integer profileCost,
        String nativeLanguage
    ) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        ensureRewards(user);

        String previousAvatar = user.getAvatarUrl();

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
        LocalDate parsedBirthDate = parseBirthDate(birthDate);
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
        if (profileCost != null) {
            int normalized = Math.max(0, profileCost);
            user.setProfileCost(normalized);
        } else {
            ensureProfileCost(user);
        }
        if (nativeLanguage != null) {
            user.setNativeLanguage(nativeLanguage);
        }

        applySpecialPrivileges(user);
        return userRepository.save(user);
    }

    public UserVerificationResult verifyUserWithSelfie(Long userId, Path selfiePath) {
        if (selfiePath == null || !Files.exists(selfiePath)) {
            throw new IllegalArgumentException("Selfie file not found");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (!hasActiveSubscription(user)) {
            throw new RuntimeException("Subscription required");
        }

        if (Boolean.TRUE.equals(user.getIsVerified())) {
            return new UserVerificationResult(true, 1.0, null);
        }

        if (user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) {
            throw new IllegalArgumentException("Avatar is required for verification");
        }

        try {
            BufferedImage avatarImage = loadImageFromSource(user.getAvatarUrl());
            BufferedImage selfieImage = ImageIO.read(selfiePath.toFile());

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

            double similarity = calculateSimilarity(avatarImage, selfieImage);
            if (similarity < VERIFICATION_THRESHOLD) {
                return new UserVerificationResult(false, 0.0, "Вы не похожи на того человека");
            }

            user.setIsVerified(true);
            user.setWasVerified(true);
            userRepository.save(user);

            return new UserVerificationResult(true, 1, null);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            logger.error("Failed to verify user {} using selfie {}", userId, selfiePath, ex);
            throw new RuntimeException("Unable to verify user at this time");
        }
    }

    public Integer getUserBalance(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getBalance();
    }

    public User purchaseCoins(Long userId, Integer amount, String paymentMethod) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        int coinsToAdd = calculateCoinsForPayment(amount, paymentMethod);
        user.setBalance(user.getBalance() + coinsToAdd);

        return userRepository.save(user);
    }

    public void updateUserBalance(Long userId, Integer newBalance) {
        userRepository.updateBalance(userId, newBalance);
    }

    public User updateCommunityNotifications(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getSettings() == null) {
            user.setSettings(new User.UserSettings());
        }
        user.getSettings().setAllowCommunityMessages(enabled);
        return userRepository.save(user);
    }

    public void deductCoins(Long userId, Integer amount) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.getBalance() < amount) {
            throw new RuntimeException("Insufficient balance");
        }

        user.setBalance(user.getBalance() - amount);
        userRepository.save(user);
    }

    public void updateOnlineStatus(Long userId, Boolean isOnline) {
        userRepository.updateOnlineStatus(userId, isOnline);
    }

    public User banUser(Long targetUserId, String reason) {
        User user = userRepository.findById(targetUserId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        user.setIsBanned(true);
        user.setBanReason(reason != null ? reason : "Блокировка администрацией");
        user.setBannedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    public User unbanUser(Long targetUserId) {
        User user = userRepository.findById(targetUserId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        user.setIsBanned(false);
        user.setBanReason(null);
        user.setBannedAt(null);
        return userRepository.save(user);
    }

    private User.UserRewards ensureRewards(User user) {
        if (user == null) {
            return null;
        }
        if (user.getRewards() == null) {
            user.setRewards(new User.UserRewards());
        }
        return user.getRewards();
    }

    private Integer calculateAge(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        int age = today.getYear() - birthDate.getYear();
        if (birthDate.plusYears(age).isAfter(today)) {
            age -= 1;
        }
        return Math.max(age, 0);
    }

    private LocalDate parseBirthDate(String birthDate) {
        if (birthDate == null || birthDate.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDate.parse(birthDate.trim());
        } catch (Exception ex) {
            logger.warn("Invalid birthDate provided: {}", birthDate);
            return null;
        }
    }

    private BufferedImage loadImageFromSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }

        try {
            if (source.startsWith("http://") || source.startsWith("https://")) {
                try (InputStream stream = new URL(source).openStream()) {
                    return ImageIO.read(stream);
                }
            }

            String normalized = source.startsWith("/") ? source.substring(1) : source;
            List<Path> candidates = new ArrayList<>();
            candidates.add(Paths.get(normalized));
            candidates.add(Paths.get(AppConfig.UPLOAD_DIR, normalized));

            if (normalized.startsWith("uploads/")) {
                String withoutPrefix = normalized.substring("uploads/".length());
                candidates.add(Paths.get(AppConfig.UPLOAD_DIR, withoutPrefix));
            }

            Path fileName = Paths.get(normalized).getFileName();
            if (fileName != null) {
                candidates.add(Paths.get(AppConfig.UPLOAD_DIR, fileName.toString()));
            }

            for (Path candidate : candidates) {
                if (candidate != null && Files.exists(candidate)) {
                    return ImageIO.read(candidate.toFile());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load image from {}", source, e);
        }

        return null;
    }

    public String mirrorExternalAvatar(String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }

        String trimmed = source.trim();
        if (trimmed.contains(AppConfig.UPLOAD_DIR)) {
            return trimmed;
        }

        try (InputStream in = new URL(trimmed).openStream()) {
            String extension = ".jpg";
            String lower = trimmed.toLowerCase();
            if (lower.contains(".png")) {
                extension = ".png";
            } else if (lower.contains(".webp")) {
                extension = ".webp";
            }

            String fileName = "vk-avatar-" + System.currentTimeMillis() + "-" + Math.abs(trimmed.hashCode()) + extension;
            Path target = Paths.get(AppConfig.UPLOAD_DIR, fileName);
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "/uploads/" + fileName;
        } catch (Exception e) {
            logger.warn("Failed to mirror external avatar {}", source, e);
            return null;
        }
    }

    private double calculateSimilarity(BufferedImage first, BufferedImage second) {
        final int size = 64;
        BufferedImage normalizedFirst = resizeImage(first, size, size);
        BufferedImage normalizedSecond = resizeImage(second, size, size);

        if (isTooBlurry(normalizedSecond)) {
            throw new IllegalArgumentException("Selfie too blurry for verification");
        }

        double pixelSimilarity = pixelSimilarity(normalizedFirst, normalizedSecond);
        double hashSimilarity = dhashSimilarity(normalizedFirst, normalizedSecond);
        double histogramSimilarity = histogramCosineSimilarity(normalizedFirst, normalizedSecond);

        double combined = (hashSimilarity * 0.5) + (histogramSimilarity * 0.3) + (pixelSimilarity * 0.2);
        return Math.max(0.0, Math.min(1.0, combined));
    }

    private BufferedImage resizeImage(BufferedImage source, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return output;
    }

    private double pixelSimilarity(BufferedImage first, BufferedImage second) {
        final int width = first.getWidth();
        final int height = first.getHeight();
        double diff = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int grayA = rgbToGray(first.getRGB(x, y));
                int grayB = rgbToGray(second.getRGB(x, y));
                diff += Math.abs(grayA - grayB) / 255.0;
            }
        }
        double similarity = 1.0 - (diff / (width * height));
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private double dhashSimilarity(BufferedImage first, BufferedImage second) {
        long hashA = calculateDHash(first);
        long hashB = calculateDHash(second);
        int distance = Long.bitCount(hashA ^ hashB);
        double similarity = 1.0 - (distance / 64.0);
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private long calculateDHash(BufferedImage image) {
        BufferedImage resized = resizeImage(image, 9, 8);
        long hash = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = rgbToGray(resized.getRGB(x, y));
                int right = rgbToGray(resized.getRGB(x + 1, y));
                boolean bit = left > right;
                hash = (hash << 1) | (bit ? 1L : 0L);
            }
        }
        return hash;
    }

    private double histogramCosineSimilarity(BufferedImage first, BufferedImage second) {
        double[] histA = grayscaleHistogram(first);
        double[] histB = grayscaleHistogram(second);

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
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private boolean hasActiveSubscription(User user) {
        if (user == null || user.getSubscription() == null) {
            return false;
        }
        Boolean active = user.getSubscription().getIsActive();
        return Boolean.TRUE.equals(active);
    }

    private double[] grayscaleHistogram(BufferedImage image) {
        double[] hist = new double[256];
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = rgbToGray(image.getRGB(x, y));
                hist[gray] += 1.0;
            }
        }
        double total = width * height;
        if (total > 0) {
            for (int i = 0; i < hist.length; i++) {
                hist[i] /= total;
            }
        }
        return hist;
    }

    private boolean isTooBlurry(BufferedImage image) {
        double variance = laplacianVariance(image);
        return variance < 35.0;
    }

    private String validateHumanPresence(BufferedImage image, boolean isAvatar) {
        BufferedImage normalized = resizeImage(image, 160, 160);
        SkinStats stats = calculateSkinStats(normalized);

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

    private SkinStats calculateSkinStats(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int total = width * height;
        int skinPixels = 0;
        int darkOnSkin = 0;
        int centerSkin = 0;
        int edgeSkin = 0;

        int minX = width, minY = height, maxX = -1, maxY = -1;

        int centerStartX = (int) (width * 0.25);
        int centerEndX = (int) (width * 0.75);
        int centerStartY = (int) (height * 0.25);
        int centerEndY = (int) (height * 0.75);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                double cb = (-0.168736 * r) + (-0.331264 * g) + (0.5 * b) + 128;
                double cr = (0.5 * r) + (-0.418688 * g) + (-0.081312 * b) + 128;

                boolean skin =
                    r > 60 && g > 40 && b > 20 &&
                        (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 15) &&
                        r > g && r > b &&
                        cr > 135 && cr < 180 &&
                        cb > 85 && cb < 135;

                if (skin) {
                    skinPixels++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);

                    int gray = rgbToGray(rgb);
                    if (gray < 70) {
                        darkOnSkin++;
                    }

                    boolean inCenter = x >= centerStartX && x <= centerEndX && y >= centerStartY && y <= centerEndY;
                    if (inCenter) {
                        centerSkin++;
                    } else if (x < 8 || x > width - 9 || y < 8 || y > height - 9) {
                        edgeSkin++;
                    }
                }
            }
        }

        double coverage = total > 0 ? (double) skinPixels / total : 0.0;
        double darkRatio = skinPixels > 0 ? (double) darkOnSkin / skinPixels : 0.0;
        double centerCoverage = skinPixels > 0 ? (double) centerSkin / skinPixels : 0.0;
        double edgeSkinRatio = skinPixels > 0 ? (double) edgeSkin / skinPixels : 0.0;

        double aspectRatio = 0.0;
        double bboxCoverage = 0.0;
        if (maxX >= minX && maxY >= minY) {
            int bboxW = (maxX - minX) + 1;
            int bboxH = (maxY - minY) + 1;
            aspectRatio = (double) bboxW / bboxH;
            bboxCoverage = (double) (bboxW * bboxH) / total;
        }

        return new SkinStats(coverage, bboxCoverage, aspectRatio, darkRatio, centerCoverage, edgeSkinRatio);
    }

    private double calculateEntropy(BufferedImage image) {
        double[] hist = grayscaleHistogram(image);
        double entropy = 0.0;
        for (double v : hist) {
            if (v > 0) {
                entropy += -v * (Math.log(v) / Math.log(2));
            }
        }
        return entropy;
    }

    private boolean looksLikeScreen(BufferedImage image) {
        double gridScore = pixelGridScore(image);
        double saturation = averageSaturation(image);
        return gridScore > 22.0 && saturation < 0.25;
    }

    private double pixelGridScore(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double accum = 0.0;
        int count = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int current = rgbToGray(image.getRGB(x, y));
                if (x + 1 < width) {
                    int right = rgbToGray(image.getRGB(x + 1, y));
                    if ((x & 1) == 0) {
                        accum += Math.abs(current - right);
                        count++;
                    }
                }
                if (y + 1 < height) {
                    int down = rgbToGray(image.getRGB(x, y + 1));
                    if ((y & 1) == 0) {
                        accum += Math.abs(current - down);
                        count++;
                    }
                }
            }
        }

        return count > 0 ? accum / count : 0.0;
    }

    private double averageSaturation(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double total = 0.0;
        int count = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                double max = Math.max(r, Math.max(g, b));
                double min = Math.min(r, Math.min(g, b));
                double s = max == 0 ? 0 : (max - min) / max;
                total += s;
                count++;
            }
        }

        return count > 0 ? total / count : 0.0;
    }

    private double laplacianVariance(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double sum = 0.0;
        double sumSq = 0.0;
        int count = 0;

        int[][] kernel = {
            {0, 1, 0},
            {1, -4, 1},
            {0, 1, 0}
        };

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double lap = 0.0;
                for (int ky = -1; ky <= 1; ky++) {
                    for (int kx = -1; kx <= 1; kx++) {
                        int gray = rgbToGray(image.getRGB(x + kx, y + ky));
                        lap += gray * kernel[ky + 1][kx + 1];
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
        double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }

    private int rgbToGray(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r + g + b) / 3;
    }

    private static class SkinStats {
        final double coverage;
        final double boundingBoxCoverage;
        final double aspectRatio;
        final double darkOnSkin;
        final double centerCoverage;
        final double edgeSkinRatio;

        SkinStats(double coverage, double boundingBoxCoverage, double aspectRatio, double darkOnSkin, double centerCoverage, double edgeSkinRatio) {
            this.coverage = coverage;
            this.boundingBoxCoverage = boundingBoxCoverage;
            this.aspectRatio = aspectRatio;
            this.darkOnSkin = darkOnSkin;
            this.centerCoverage = centerCoverage;
            this.edgeSkinRatio = edgeSkinRatio;
        }
    }

    private boolean isCommunityMember(Long vkId) {
        if (vkId == null) {
            return false;
        }
        try {
            String url = "https://api.vk.com/method/groups.isMember"
                + "?group_id=" + AppConfig.VK_COMMUNITY_GROUP_ID
                + "&user_id=" + URLEncoder.encode(String.valueOf(vkId), java.nio.charset.StandardCharsets.UTF_8)
                + "&extended=0"
                + "&v=" + AppConfig.VK_API_VERSION
                + "&access_token=" + URLEncoder.encode(AppConfig.VK_COMMUNITY_ACCESS_TOKEN, java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body == null || body.isBlank()) {
                logger.warn("Empty response from VK groups.isMember");
                return false;
            }
            io.vertx.core.json.JsonObject json = new io.vertx.core.json.JsonObject(body);
            if (json.containsKey("error")) {
                logger.warn("VK groups.isMember error: {}", json.getJsonObject("error"));
                return false;
            }
            if (json.containsKey("response")) {
                Object resp = json.getValue("response");
                if (resp instanceof Number) {
                    return ((Number) resp).intValue() == 1;
                }
                if (resp instanceof io.vertx.core.json.JsonObject) {
                    io.vertx.core.json.JsonObject respObj = (io.vertx.core.json.JsonObject) resp;
                    return respObj.getInteger("member", 0) == 1;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to check VK community membership for {}", vkId, e);
        }
        return false;
    }

    private void ensureProfileCost(User user) {
        if (user.getProfileCost() == null || user.getProfileCost() < 0) {
            user.setProfileCost(AppConfig.ANONYMOUS_CHAT_CREATION_COST);
        }
    }

    private void applySpecialPrivileges(User user) {
        if (user == null || user.getVkId() == null) {
            return;
        }

        if (ADMIN_VK_IDS.contains(user.getVkId())) {
            user.setIsAdmin(true);
            if (user.getBalance() == null || user.getBalance() < 1000) {
                user.setBalance(1000);
            }
        } else if (user.getIsAdmin() == null) {
            user.setIsAdmin(false);
        }
    }

    public List<User> getOnlineUsers() {
        return userRepository.findOnlineUsers();
    }

    public List<User> findUsersForMatching(User.Gender gender, Integer minAge, Integer maxAge, String city) {
        return userRepository.findForMatching(gender, minAge, maxAge, city);
    }

    public UserStats getUserStats(Long userId) {
        return new UserStats(
            0, // totalChats
            0, // activeChats
            0, // totalMessages
            0, // likesReceived
            0, // profileViews
            0  // matchesFound
        );
    }

    public OnlineStats getOnlineStats() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.findOnlineUsers().size();

        return new OnlineStats(
            0, // anonymousChats - будет подсчитываться в ChatService
            (int) totalUsers,
            (int) activeUsers
        );
    }

    public RewardStatus getRewardStatus(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        ensureRewards(user);
        return buildRewardStatus(user);
    }

    public RewardClaimResult claimReward(Long userId, RewardType type, boolean confirmed) {
        if (type == null) {
            throw new RuntimeException("Unknown reward type");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        ensureRewards(user);

        int rewardAmount;
        switch (type) {
            case AD:
                if (!confirmed) {
                    throw new RuntimeException("Ad was not confirmed");
                }
                rewardAmount = AppConfig.AD_REWARD_AMOUNT;
                user.getRewards().setLastAdRewardAt(LocalDateTime.now());
                break;
            case COMMUNITY:
                if (Boolean.TRUE.equals(user.getRewards().getSubscriptionBonusClaimed())) {
                    return new RewardClaimResult(
                        user.getBalance(),
                        0,
                        buildRewardStatus(user)
                    );
                }
                if (user.getVkId() == null) {
                    throw new RuntimeException("VK id is required");
                }
                if (!isCommunityMember(user.getVkId())) {
                    throw new RuntimeException("Community subscription required");
                }
                rewardAmount = AppConfig.SUBSCRIPTION_REWARD_AMOUNT;
                user.getRewards().setSubscriptionBonusClaimed(true);
                break;
            default:
                throw new RuntimeException("Unsupported reward type");
        }

        if (user.getBalance() == null) {
            user.setBalance(0);
        }
        user.setBalance(user.getBalance() + rewardAmount);
        User saved = userRepository.save(user);

        return new RewardClaimResult(
            saved.getBalance(),
            rewardAmount,
            buildRewardStatus(saved)
        );
    }

    private RewardStatus buildRewardStatus(User user) {
        ensureRewards(user);
        boolean subscriptionClaimed = user.getRewards() != null
            && Boolean.TRUE.equals(user.getRewards().getSubscriptionBonusClaimed());

        return new RewardStatus(
            true,
            null,
            !subscriptionClaimed,
            subscriptionClaimed
        );
    }

    public static class UserVerificationResult {
        private final boolean verified;
        private final double similarity;
        private final String reason;

        public UserVerificationResult(boolean verified, double similarity, String reason) {
            this.verified = verified;
            this.similarity = similarity;
            this.reason = reason;
        }

        public boolean isVerified() { return verified; }
        public double getSimilarity() { return similarity; }
        public String getReason() { return reason; }
    }

    public enum RewardType {
        AD,
        COMMUNITY
    }

    public static class RewardStatus {
        private final boolean adAvailable;
        private final Integer adCooldownSeconds;
        private final boolean subscriptionAvailable;
        private final boolean subscriptionClaimed;

        public RewardStatus(
            boolean adAvailable,
            Integer adCooldownSeconds,
            boolean subscriptionAvailable,
            boolean subscriptionClaimed
        ) {
            this.adAvailable = adAvailable;
            this.adCooldownSeconds = adCooldownSeconds;
            this.subscriptionAvailable = subscriptionAvailable;
            this.subscriptionClaimed = subscriptionClaimed;
        }

        public boolean isAdAvailable() { return adAvailable; }
        public Integer getAdCooldownSeconds() { return adCooldownSeconds; }
        public boolean isSubscriptionAvailable() { return subscriptionAvailable; }
        public boolean isSubscriptionClaimed() { return subscriptionClaimed; }
    }

    public static class RewardClaimResult {
        private final int balance;
        private final int rewardedAmount;
        private final RewardStatus rewards;

        public RewardClaimResult(int balance, int rewardedAmount, RewardStatus rewards) {
            this.balance = balance;
            this.rewardedAmount = rewardedAmount;
            this.rewards = rewards;
        }

        public int getBalance() { return balance; }
        public int getRewardedAmount() { return rewardedAmount; }
        public RewardStatus getRewards() { return rewards; }
    }

    private int calculateCoinsForPayment(Integer amount, String paymentMethod) {
        switch (paymentMethod) {
            case "vk_pay":
                return amount * AppConfig.VK_PAY_COIN_RATE; // 1 рубль = 100 фиан
            case "votes":
                return amount * AppConfig.VOTES_COIN_RATE;  // 1 голос = 10 фиан
            default:
                return amount;
        }
    }

    public static class UserStats {
        private final int totalChats;
        private final int activeChats;
        private final int totalMessages;
        private final int likesReceived;
        private final int profileViews;
        private final int matchesFound;

        public UserStats(int totalChats, int activeChats, int totalMessages,
                        int likesReceived, int profileViews, int matchesFound) {
            this.totalChats = totalChats;
            this.activeChats = activeChats;
            this.totalMessages = totalMessages;
            this.likesReceived = likesReceived;
            this.profileViews = profileViews;
            this.matchesFound = matchesFound;
        }

        public int getTotalChats() { return totalChats; }
        public int getActiveChats() { return activeChats; }
        public int getTotalMessages() { return totalMessages; }
        public int getLikesReceived() { return likesReceived; }
        public int getProfileViews() { return profileViews; }
        public int getMatchesFound() { return matchesFound; }
    }

    public static class OnlineStats {
        private final int anonymousChats;
        private final int totalUsers;
        private final int activeUsers;

        public OnlineStats(int anonymousChats, int totalUsers, int activeUsers) {
            this.anonymousChats = anonymousChats;
            this.totalUsers = totalUsers;
            this.activeUsers = activeUsers;
        }

        public int getAnonymousChats() { return anonymousChats; }
        public int getTotalUsers() { return totalUsers; }
        public int getActiveUsers() { return activeUsers; }
    }
}
