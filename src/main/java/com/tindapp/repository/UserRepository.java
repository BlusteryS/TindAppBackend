package com.tindapp.repository;

import com.tindapp.model.User;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends Repository<User, Long> {

    Future<Optional<User>> findByVkId(Long vkId);

    Future<Long> countOnlineUsers();

    Future<List<User>> findForMatching(Long viewerId, User.Gender gender, Integer minAge, Integer maxAge, String city, Boolean verifiedOnly, int page, int limit);

    Future<Long> countForMatching(Long viewerId, User.Gender gender, Integer minAge, Integer maxAge, String city, Boolean verifiedOnly);

    Future<Void> updateOnlineStatus(Long userId, boolean isOnline);

    Future<Void> refreshOnlineUsers(Collection<Long> userIds);

    Future<Void> markStaleOnlineUsersOffline(Duration ttl);

    Future<Void> markAllOffline();

    Future<Void> updateBalance(Long userId, Integer balance);

    @Override
    Future<Long> count();
}
