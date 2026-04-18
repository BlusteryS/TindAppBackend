package com.tindapp.repository;

import com.tindapp.model.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends Repository<User, Long> {

    Optional<User> findByVkId(Long vkId);

    long countOnlineUsers();

    List<User> findForMatching(Long viewerId, User.Gender gender, Integer minAge, Integer maxAge, String city, Boolean verifiedOnly, int page, int limit);

    long countForMatching(Long viewerId, User.Gender gender, Integer minAge, Integer maxAge, String city, Boolean verifiedOnly);

    void updateOnlineStatus(Long userId, boolean isOnline);

    void updateBalance(Long userId, Integer balance);

    @Override
    long count();
}
