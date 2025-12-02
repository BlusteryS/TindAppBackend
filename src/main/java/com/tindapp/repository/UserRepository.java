package com.tindapp.repository;

import com.tindapp.model.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends Repository<User, Long> {

    Optional<User> findByVkId(Long vkId);

    List<User> findOnlineUsers();

    List<User> findByGender(User.Gender gender);

    List<User> findByAgeRange(Integer minAge, Integer maxAge);

    List<User> findByCity(String city);

    List<User> findForMatching(User.Gender gender, Integer minAge, Integer maxAge, String city, Boolean verifiedOnly);
    List<User> findForMatching(User.Gender gender, Integer minAge, Integer maxAge, String city, Boolean verifiedOnly, int page, int limit);

    void updateOnlineStatus(Long userId, boolean isOnline);

    void updateBalance(Long userId, Integer balance);

    long count();
}
