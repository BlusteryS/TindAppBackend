package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.DateTimeUtils;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

public class ProfileService {

    private static final int MIN_AGE = 18;
    private static final int MAX_AGE = 80;

    private final UserRepository userRepository;

    public ProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ProfileSearchResult searchProfiles(Long viewerId, ProfileFilters rawFilters, int page, int limit) {
        User viewer = userRepository.findById(viewerId)
            .orElseThrow(() -> new RuntimeException("Viewer not found"));
        return searchProfiles(viewer, rawFilters, page, limit);
    }

    public ProfileSearchResult searchProfiles(User viewer, ProfileFilters rawFilters, int page, int limit) {
        if (page < 1) {
            page = 1;
        }
        if (limit < 1) {
            limit = 12;
        }

        ProfileFilters filters = normalizeFilters(rawFilters, viewer);

        User.Gender genderEnum = toGenderEnum(filters.getGender());
        List<User> candidates = userRepository.findForMatching(genderEnum, filters.getMinAge(), filters.getMaxAge(), filters.getCity(), filters.isVerifiedOnly(), page, limit * 2).stream()
            .filter(user -> !Objects.equals(user.getId(), viewer.getId()))
            .filter(user -> matchesFilters(viewer, user, filters))
            .collect(Collectors.toList());

        Comparator<User> comparator = Comparator
            .comparingInt((User candidate) -> filters.isPrioritizeCity() && isSameCity(viewer, candidate) ? 0 : 1)
            .thenComparingLong(this::lastSeenRank)
            .thenComparingDouble(candidate -> deterministicOrder(viewer.getId(), candidate.getId()));

        candidates.sort(comparator);

        int total = candidates.size();
        int fromIndex = Math.min((page - 1) * limit, total);
        int toIndex = Math.min(fromIndex + limit, total);

        List<ProfileCard> cards = candidates.subList(fromIndex, toIndex).stream()
            .map(candidate -> toProfileCard(viewer, candidate))
            .collect(Collectors.toList());

        return new ProfileSearchResult(cards, total);
    }

    public ProfileFilters parseFilters(MultiMap params, User viewer) {
        ProfileFilters filters = defaultFilters(viewer);
        if (params == null) {
            return filters;
        }

        Optional.ofNullable(params.get("gender")).ifPresent(filters::setGender);
        Optional.ofNullable(params.get("city")).ifPresent(city -> filters.setCity(city.trim()));
        Optional.ofNullable(params.get("minAge")).ifPresent(value -> filters.setMinAge(parseInt(value, filters.getMinAge())));
        Optional.ofNullable(params.get("maxAge")).ifPresent(value -> filters.setMaxAge(parseInt(value, filters.getMaxAge())));
        Optional.ofNullable(params.get("verifiedOnly")).ifPresent(value -> filters.setVerifiedOnly(Boolean.parseBoolean(value)));
        Optional.ofNullable(params.get("prioritizeCity")).ifPresent(value -> filters.setPrioritizeCity(Boolean.parseBoolean(value)));

        return normalizeFilters(filters, viewer);
    }

    public ProfileFilters parseFilters(JsonObject json, User viewer) {
        ProfileFilters filters = defaultFilters(viewer);
        if (json == null) {
            return filters;
        }

        filters.setGender(json.getString("gender", filters.getGender()));
        filters.setCity(trimToNull(json.getString("city", filters.getCity())));
        filters.setVerifiedOnly(json.getBoolean("verifiedOnly", filters.isVerifiedOnly()));
        filters.setPrioritizeCity(json.getBoolean("prioritizeCity", filters.isPrioritizeCity()));

        JsonArray ageRange = json.getJsonArray("ageRange");
        if (ageRange != null && ageRange.size() == 2) {
            filters.setMinAge(parseInt(String.valueOf(ageRange.getValue(0)), filters.getMinAge()));
            filters.setMaxAge(parseInt(String.valueOf(ageRange.getValue(1)), filters.getMaxAge()));
        }

        return normalizeFilters(filters, viewer);
    }

    private User.Gender toGenderEnum(String gender) {
        if (gender == null) {
            return null;
        }
        if ("any".equalsIgnoreCase(gender)) {
            return null; // no gender filter
        }
        switch (gender.toLowerCase()) {
            case "male":
                return User.Gender.MALE;
            case "female":
                return User.Gender.FEMALE;
            default:
                return User.Gender.OTHER;
        }
    }

    public ProfileCard toProfileCard(User viewer, User candidate) {
        boolean sameCity = isSameCity(viewer, candidate);
        String lastSeen = candidate.getLastSeenDateTime() != null ?
            DateTimeUtils.formatToIso(candidate.getLastSeenDateTime()) :
            null;

        boolean viewerHasSubscription = viewer != null
            && viewer.getSubscription() != null
            && Boolean.TRUE.equals(viewer.getSubscription().getIsActive());
        boolean hasActiveSubscription = candidate.getSubscription() != null
            && Boolean.TRUE.equals(candidate.getSubscription().getIsActive());
        int baseCost = candidate.getProfileCost() != null
            ? candidate.getProfileCost()
            : AppConfig.ANONYMOUS_CHAT_CREATION_COST;
        int cost = viewerHasSubscription ? 0 : baseCost;

        return new ProfileCard(
            candidate.getId(),
            candidate.getFirstName(),
            candidate.getLastName(),
            resolveAge(candidate),
            candidate.getCity(),
            candidate.getCountry(),
            candidate.getAvatarUrl(),
            candidate.isVerified(),
            candidate.isOnline(),
            lastSeen,
            candidate.getBio(),
            candidate.getGender(),
            cost,
            sameCity,
            hasActiveSubscription
        );
    }

    public boolean matchesFilters(User viewer, User candidate, ProfileFilters filters) {
        if (filters == null) {
            return true;
        }

        if (filters.getGender() != null && !"any".equals(filters.getGender())) {
            if (!Objects.equals(filters.getGender(), safeLower(candidate.getGender()))) {
                return false;
            }
        }

        if (filters.isVerifiedOnly() && !candidate.isVerified()) {
            return false;
        }

        Integer age = resolveAge(candidate);
        if (age != null) {
            if (age < filters.getMinAge() || age > filters.getMaxAge()) {
                return false;
            }
        }

        return true;
    }

    public ProfileFilters normalizeFilters(ProfileFilters filters, User viewer) {
        ProfileFilters result = filters != null ? filters : defaultFilters(viewer);

        int minAge = Math.max(MIN_AGE, result.getMinAge());
        int maxAge = Math.min(MAX_AGE, result.getMaxAge());
        if (minAge > maxAge) {
            minAge = MIN_AGE;
            maxAge = MAX_AGE;
        }
        result.setMinAge(minAge);
        result.setMaxAge(maxAge);

        if (result.getGender() == null || result.getGender().isBlank()) {
            result.setGender("any");
        } else {
            result.setGender(result.getGender().toLowerCase());
        }

        if (!result.isPrioritizeCity()) {
            result.setCity(null);
        } else if ((result.getCity() == null || result.getCity().isBlank()) && viewer != null) {
            result.setCity(trimToNull(viewer.getCity()));
        }

        return result;
    }

    private Integer resolveAge(User user) {
        if (user == null) {
            return null;
        }
        if (user.getAge() != null) {
            return user.getAge();
        }
        if (user.getBirthDate() != null) {
            LocalDate today = LocalDate.now();
            int age = today.getYear() - user.getBirthDate().getYear();
            if (user.getBirthDate().plusYears(age).isAfter(today)) {
                age -= 1;
            }
            return Math.max(age, 0);
        }
        return null;
    }

    private ProfileFilters defaultFilters(User viewer) {
        ProfileFilters filters = new ProfileFilters();
        filters.setGender("any");
        filters.setMinAge(MIN_AGE);
        filters.setMaxAge(MAX_AGE);
        filters.setCity(viewer != null ? viewer.getCity() : null);
        filters.setVerifiedOnly(false);
        filters.setPrioritizeCity(true);
        return filters;
    }

    private boolean isSameCity(User viewer, User candidate) {
        if (viewer == null || viewer.getCity() == null || candidate.getCity() == null) {
            return false;
        }
        return viewer.getCity().equalsIgnoreCase(candidate.getCity());
    }

    private long lastSeenRank(User user) {
        LocalDateTime reference = user.isOnline() ? LocalDateTime.now() : user.getLastSeenDateTime();
        if (reference == null) {
            return Long.MAX_VALUE;
        }
        return Long.MAX_VALUE - reference.toEpochSecond(ZoneOffset.UTC);
    }

    private double deterministicOrder(Long viewerId, Long candidateId) {
        long seed = Objects.hash(viewerId, candidateId, LocalDate.now().getDayOfYear());
        Random random = new Random(seed);
        return random.nextDouble();
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeLower(String value) {
        return value != null ? value.toLowerCase() : null;
    }

    public static class ProfileFilters {
        private String gender;
        private int minAge;
        private int maxAge;
        private String city;
        private boolean verifiedOnly;
        private boolean prioritizeCity;

        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }

        public int getMinAge() { return minAge; }
        public void setMinAge(int minAge) { this.minAge = minAge; }

        public int getMaxAge() { return maxAge; }
        public void setMaxAge(int maxAge) { this.maxAge = maxAge; }

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }

        public boolean isVerifiedOnly() { return verifiedOnly; }
        public void setVerifiedOnly(boolean verifiedOnly) { this.verifiedOnly = verifiedOnly; }

        public boolean isPrioritizeCity() { return prioritizeCity; }
        public void setPrioritizeCity(boolean prioritizeCity) { this.prioritizeCity = prioritizeCity; }

        @Override
        public String toString() {
            return "ProfileFilters{" +
                "gender='" + gender + '\'' +
                ", minAge=" + minAge +
                ", maxAge=" + maxAge +
                ", city='" + city + '\'' +
                ", verifiedOnly=" + verifiedOnly +
                ", prioritizeCity=" + prioritizeCity +
                '}';
        }
    }

    public static class ProfileCard {
        private final Long id;
        private final String firstName;
        private final String lastName;
        private final Integer age;
        private final String city;
        private final String country;
        private final String avatarUrl;
        private final boolean isVerified;
        private final boolean isOnline;
        private final String lastSeen;
        private final String bio;
        private final String gender;
        private final int cost;
        private final boolean sameCity;
        private final boolean hasActiveSubscription;

        public ProfileCard(Long id, String firstName, String lastName, Integer age, String city, String country,
                           String avatarUrl, boolean isVerified, boolean isOnline, String lastSeen,
                           String bio, String gender, int cost, boolean sameCity, boolean hasActiveSubscription) {
            this.id = id;
            this.firstName = firstName;
            this.lastName = lastName;
            this.age = age;
            this.city = city;
            this.country = country;
            this.avatarUrl = avatarUrl;
            this.isVerified = isVerified;
            this.isOnline = isOnline;
            this.lastSeen = lastSeen;
            this.bio = bio;
            this.gender = gender;
            this.cost = cost;
            this.sameCity = sameCity;
            this.hasActiveSubscription = hasActiveSubscription;
        }

        public Long getId() { return id; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public Integer getAge() { return age; }
        public String getCity() { return city; }
        public String getCountry() { return country; }
        public String getAvatarUrl() { return avatarUrl; }
        public boolean getIsVerified() { return isVerified; }
        public boolean getIsOnline() { return isOnline; }
        public String getLastSeen() { return lastSeen; }
        public String getBio() { return bio; }
        public String getGender() { return gender; }
        public int getCost() { return cost; }
        public boolean getSameCity() { return sameCity; }
        public boolean getHasActiveSubscription() { return hasActiveSubscription; }
    }

    public static class ProfileSearchResult {
        private final List<ProfileCard> profiles;
        private final int total;

        public ProfileSearchResult(List<ProfileCard> profiles, int total) {
            this.profiles = profiles;
            this.total = total;
        }

        public List<ProfileCard> getProfiles() { return profiles; }
        public int getTotal() { return total; }
    }
}
