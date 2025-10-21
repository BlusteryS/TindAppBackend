package com.tindapp.repository;

import com.tindapp.model.User;

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
    public User save(User user) {
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
    public Optional<User> findById(Long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByVkId(Long vkId) {
        Long userId = vkIdToUserId.get(vkId);
        return userId != null ? findById(userId) : Optional.empty();
    }

    @Override
    public List<User> findAll() {
        return new ArrayList<>(users.values());
    }

    @Override
    public List<User> findAll(int page, int limit) {
        List<User> allUsers = findAll();
        int start = (page - 1) * limit;
        int end = Math.min(start + limit, allUsers.size());

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
    public List<User> findByGender(User.Gender gender) {
        return users.values().stream()
                .filter(user -> gender.equals(user.getGender()))
                .collect(Collectors.toList());
    }

    @Override
    public List<User> findByAgeRange(Integer minAge, Integer maxAge) {
        return users.values().stream()
                .filter(user -> user.getAge() != null)
                .filter(user -> user.getAge() >= minAge && user.getAge() <= maxAge)
                .collect(Collectors.toList());
    }

    @Override
    public List<User> findByCity(String city) {
        return users.values().stream()
                .filter(user -> city.equals(user.getCity()))
                .collect(Collectors.toList());
    }

    @Override
    public List<User> findForMatching(User.Gender gender, Integer minAge, Integer maxAge, String city) {
        return users.values().stream()
                .filter(user -> Boolean.TRUE.equals(user.getIsVisible()))
                .filter(user -> Boolean.TRUE.equals(user.getSettings().getAllowMessages()))
                .filter(user -> gender == null || gender.equals(user.getGender()))
                .filter(user -> user.getAge() == null || (user.getAge() >= minAge && user.getAge() <= maxAge))
                .filter(user -> city == null || city.equals(user.getCity()))
                .collect(Collectors.toList());
    }

    @Override
    public void updateOnlineStatus(Long userId, boolean isOnline) {
        User user = users.get(userId);
        if (user != null) {
            user.updateOnlineStatus(isOnline);
        }
    }

    @Override
    public void updateBalance(Long userId, Integer balance) {
        User user = users.get(userId);
        if (user != null) {
            user.setBalance(balance);
            user.setUpdatedAtDateTime(LocalDateTime.now());
        }
    }

    @Override
    public void deleteById(Long id) {
        User user = users.remove(id);
        if (user != null && user.getVkId() != null) {
            vkIdToUserId.remove(user.getVkId());
        }
    }

    @Override
    public boolean existsById(Long id) {
        return users.containsKey(id);
    }

    @Override
    public long count() {
        return users.size();
    }
}
