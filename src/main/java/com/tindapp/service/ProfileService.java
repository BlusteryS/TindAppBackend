package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.DateTimeUtils;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ProfileService {

    private static final int MIN_AGE = 18;
    private static final int MAX_AGE = 80;

    private final UserRepository userRepository;

    public ProfileService(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ProfileSearchResult searchProfiles(final Long viewerId, final ProfileFilters rawFilters, final int page, final int limit) {
        final User viewer = userRepository.findById(viewerId)
            .orElseThrow(() -> new RuntimeException("Viewer not found"));
        return searchProfiles(viewer, rawFilters, page, limit);
    }

    public ProfileSearchResult searchProfiles(final User viewer, final ProfileFilters rawFilters, int page, int limit) {
        if (page < 1) {
            page = 1;
        }
        if (limit < 1) {
            limit = 12;
        }

        final ProfileFilters filters = normalizeFilters(rawFilters, viewer);

        final User.Gender genderEnum = toGenderEnum(filters.getGender());
        final List<User> candidates = userRepository.findForMatching(
            viewer.getId(),
            genderEnum,
            filters.getMinAge(),
            filters.getMaxAge(),
            filters.getCity(),
            filters.isVerifiedOnly(),
            page,
            limit
        );
        final int total = Math.toIntExact(userRepository.countForMatching(
            viewer.getId(),
            genderEnum,
            filters.getMinAge(),
            filters.getMaxAge(),
            filters.getCity(),
            filters.isVerifiedOnly()
        ));

        final int chatCost = hasActiveSubscription(viewer) ? 0 : ChatPricingPolicy.calculateCost((int) userRepository.countOnlineUsers());
        final List<ProfileCard> cards = candidates.stream()
            .map(candidate -> toProfileCard(viewer, candidate, chatCost))
            .toList();

        return new ProfileSearchResult(cards, total);
    }

    public ProfileFilters parseFilters(final MultiMap params, final User viewer) {
        final ProfileFilters filters = defaultFilters(viewer);
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

    public ProfileFilters parseFilters(final JsonObject json, final User viewer) {
        final ProfileFilters filters = defaultFilters(viewer);
        if (json == null) {
            return filters;
        }

        filters.setGender(json.getString("gender", filters.getGender()));
        filters.setCity(trimToNull(json.getString("city", filters.getCity())));
        filters.setVerifiedOnly(json.getBoolean("verifiedOnly", filters.isVerifiedOnly()));
        filters.setPrioritizeCity(json.getBoolean("prioritizeCity", filters.isPrioritizeCity()));

        final JsonArray ageRange = json.getJsonArray("ageRange");
        if (ageRange != null && ageRange.size() == 2) {
            filters.setMinAge(parseInt(String.valueOf(ageRange.getValue(0)), filters.getMinAge()));
            filters.setMaxAge(parseInt(String.valueOf(ageRange.getValue(1)), filters.getMaxAge()));
        }

        return normalizeFilters(filters, viewer);
    }

    private User.Gender toGenderEnum(final String gender) {
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

    public ProfileCard toProfileCard(final User viewer, final User candidate) {
        final int chatCost = hasActiveSubscription(viewer) ? 0 : ChatPricingPolicy.calculateCost((int) userRepository.countOnlineUsers());
        return toProfileCard(viewer, candidate, chatCost);
    }

    private ProfileCard toProfileCard(final User viewer, final User candidate, final int cost) {
        final boolean sameCity = isSameCity(viewer, candidate);
        final String lastSeen = candidate.getLastSeenDateTime() != null ?
            DateTimeUtils.formatToIso(candidate.getLastSeenDateTime()) :
            null;

        final boolean hasActiveSubscription = candidate.getSubscription() != null
            && Boolean.TRUE.equals(candidate.getSubscription().getIsActive());

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

    private boolean hasActiveSubscription(final User user) {
        return user != null
            && user.getSubscription() != null
            && Boolean.TRUE.equals(user.getSubscription().getIsActive());
    }

    public boolean matchesFilters(final User viewer, final User candidate, final ProfileFilters filters) {
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

        final Integer age = resolveAge(candidate);
        if (age != null) {
            return age >= filters.getMinAge() && age <= filters.getMaxAge();
        }

        return true;
    }

    public ProfileFilters normalizeFilters(final ProfileFilters filters, final User viewer) {
        final ProfileFilters result = filters != null ? filters : defaultFilters(viewer);

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

    private Integer resolveAge(final User user) {
        if (user == null) {
            return null;
        }
        if (user.getAge() != null) {
            return user.getAge();
        }
        if (user.getBirthDate() != null) {
            final LocalDate today = LocalDate.now();
            int age = today.getYear() - user.getBirthDate().getYear();
            if (user.getBirthDate().plusYears(age).isAfter(today)) {
                age -= 1;
            }
            return Math.max(age, 0);
        }
        return null;
    }

    private ProfileFilters defaultFilters(final User viewer) {
        final ProfileFilters filters = new ProfileFilters();
        filters.setGender("any");
        filters.setMinAge(MIN_AGE);
        filters.setMaxAge(MAX_AGE);
        filters.setCity(viewer != null ? viewer.getCity() : null);
        filters.setVerifiedOnly(false);
        filters.setPrioritizeCity(true);
        return filters;
    }

    private boolean isSameCity(final User viewer, final User candidate) {
        if (viewer == null || viewer.getCity() == null || candidate.getCity() == null) {
            return false;
        }
        return viewer.getCity().equalsIgnoreCase(candidate.getCity());
    }

    private int parseInt(final String value, final int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }

    private String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeLower(final String value) {
        return value != null ? value.toLowerCase() : null;
    }

    public static class ProfileFilters {
        private String gender;
        private int minAge;
        private int maxAge;
        private String city;
        private boolean verifiedOnly;
        private boolean prioritizeCity;

        public String getGender() {
            return gender;
        }

        public void setGender(final String gender) {
            this.gender = gender;
        }

        public int getMinAge() {
            return minAge;
        }

        public void setMinAge(final int minAge) {
            this.minAge = minAge;
        }

        public int getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(final int maxAge) {
            this.maxAge = maxAge;
        }

        public String getCity() {
            return city;
        }

        public void setCity(final String city) {
            this.city = city;
        }

        public boolean isVerifiedOnly() {
            return verifiedOnly;
        }

        public void setVerifiedOnly(final boolean verifiedOnly) {
            this.verifiedOnly = verifiedOnly;
        }

        public boolean isPrioritizeCity() {
            return prioritizeCity;
        }

        public void setPrioritizeCity(final boolean prioritizeCity) {
            this.prioritizeCity = prioritizeCity;
        }

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

    public record ProfileCard(Long id, String firstName, String lastName, Integer age, String city, String country, String avatarUrl, boolean isVerified, boolean isOnline, String lastSeen, String bio, String gender, int cost, boolean sameCity, boolean hasActiveSubscription) {
    }

    public record ProfileSearchResult(List<ProfileCard> profiles, int total) {
    }
}
