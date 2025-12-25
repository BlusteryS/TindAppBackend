package com.tindapp.repository;

import com.tindapp.model.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryUserRepository implements UserRepository {

    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final Map<Long, Long> vkIdToUserId = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public User save(final User user) {
        if (user.getId() == null) {
            user.setId(idGenerator.getAndIncrement());
        }
        user.setUpdatedAtDateTime(LocalDateTime.now());
        users.put(user.getId(), user);
        if (user.getVkId() != null) {
            vkIdToUserId.put(user.getVkId(), user.getId());
        }
        return user;
    }

    @Override
    public Optional<User> findById(final Long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByVkId(final Long vkId) {
        final Long userId = vkIdToUserId.get(vkId);
        return userId != null ? findById(userId) : Optional.empty();
    }

    @Override
    public List<User> findAll() {
        return new ArrayList<>(users.values());
    }

    @Override
    public List<User> findAll(final int page, final int limit) {
        final List<User> allUsers = findAll();
        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, allUsers.size());

        if (start >= allUsers.size()) {
            return new ArrayList<>();
        }

        return allUsers.subList(start, end);
    }

    @Override
    public List<User> findOnlineUsers() {
        return users.values().stream()
            .filter(user -> Boolean.TRUE.equals(user.getIsOnline()))
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findByGender(final User.Gender gender) {
        return users.values().stream()
            .filter(user -> gender.equals(user.getGender()))
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findByAgeRange(final Integer minAge, final Integer maxAge) {
        return users.values().stream()
            .filter(user -> {
                final Integer age = resolveAge(user);
                return age != null && age >= minAge && age <= maxAge;
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findByCity(final String city) {
        return users.values().stream()
            .filter(user -> city.equals(user.getCity()))
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findForMatching(final User.Gender gender, final Integer minAge, final Integer maxAge, final String city, final Boolean verifiedOnly) {
        return users.values().stream()
            .filter(user -> Boolean.TRUE.equals(user.getIsVisible()))
            .filter(user -> {
                if (user.getSettings() == null) {
                    return false;
                }
                return Boolean.TRUE.equals(user.getSettings().getAllowMessages());
            })
            .filter(user -> Boolean.TRUE.equals(user.getSettings().getAllowMessages()))
            .filter(user -> verifiedOnly == null || !verifiedOnly || Boolean.TRUE.equals(user.getIsVerified()))
            .filter(user -> gender == null || gender.equals(user.getGender()))
            .filter(user -> {
                final Integer age = resolveAge(user);
                return age == null || (age >= minAge && age <= maxAge);
            })
            .filter(user -> city == null || city.equals(user.getCity()))
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findForMatching(final User.Gender gender, final Integer minAge, final Integer maxAge, final String city, final Boolean verifiedOnly, final int page, final int limit) {
        final List<User> all = findForMatching(gender, minAge, maxAge, city, verifiedOnly);
        final int start = Math.max(0, (page - 1) * limit);
        final int end = Math.min(start + limit, all.size());
        if (start >= all.size()) {
            return new ArrayList<>();
        }
        return all.subList(start, end);
    }

    @Override
    public void updateOnlineStatus(final Long userId, final boolean isOnline) {
        final User user = users.get(userId);
        if (user != null) {
            user.updateOnlineStatus(isOnline);
        }
    }

    @Override
    public void updateBalance(final Long userId, final Integer balance) {
        final User user = users.get(userId);
        if (user != null) {
            user.setBalance(balance);
            user.setUpdatedAtDateTime(LocalDateTime.now());
        }
    }

    @Override
    public void deleteById(final Long id) {
        final User user = users.remove(id);
        if (user != null && user.getVkId() != null) {
            vkIdToUserId.remove(user.getVkId());
        }
    }

    @Override
    public boolean existsById(final Long id) {
        return users.containsKey(id);
    }

    @Override
    public long count() {
        return users.size();
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
}
