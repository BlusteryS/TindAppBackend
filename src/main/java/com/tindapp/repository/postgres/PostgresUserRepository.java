package com.tindapp.repository.postgres;

import com.tindapp.model.User;
import com.tindapp.repository.UserRepository;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class PostgresUserRepository extends AbstractPostgresRepository implements UserRepository {

    public PostgresUserRepository(PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS users (
                id BIGSERIAL PRIMARY KEY,
                vk_id BIGINT UNIQUE,
                data JSONB NOT NULL
            )
            """);
    }

    @Override
    public User save(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User is null");
        }

        if (user.getCreatedAtDateTime() == null) {
            user.setCreatedAtDateTime(LocalDateTime.now());
        }
        user.setUpdatedAtDateTime(LocalDateTime.now());

        boolean isNew = user.getId() == null;
        JsonObject payload = toJson(user);
        RowSet<Row> rows;

        if (isNew) {
            rows = execute(
                "INSERT INTO users (vk_id, data) VALUES ($1, $2::jsonb) " +
                    "ON CONFLICT (vk_id) DO UPDATE SET data = EXCLUDED.data " +
                    "RETURNING id",
                Tuple.of(user.getVkId(), payload)
            );
        } else {
            rows = execute(
                "INSERT INTO users (id, vk_id, data) VALUES ($1, $2, $3::jsonb) " +
                    "ON CONFLICT (id) DO UPDATE SET vk_id = EXCLUDED.vk_id, data = EXCLUDED.data " +
                    "RETURNING id",
                Tuple.of(user.getId(), user.getVkId(), payload)
            );
        }

        Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        if (row != null) {
            Long generatedId = row.getLong("id");
            user.setId(generatedId);
        }

        if (isNew && user.getId() != null) {
            JsonObject updatedPayload = toJson(user);
            execute(
                "UPDATE users SET data = $2::jsonb, vk_id = $3 WHERE id = $1",
                Tuple.of(user.getId(), updatedPayload, user.getVkId())
            );
        }
        return user;
    }

    @Override
    public Optional<User> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        RowSet<Row> rows = execute("SELECT id, data FROM users WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        Row row = rows.iterator().next();
        User user = mapRow(row, User.class);
        if (user != null && user.getId() == null) {
            user.setId(row.getLong("id"));
        }
        return Optional.ofNullable(user);
    }

    @Override
    public Optional<User> findByVkId(Long vkId) {
        if (vkId == null) {
            return Optional.empty();
        }
        RowSet<Row> rows = execute("SELECT id, data FROM users WHERE vk_id = $1 LIMIT 1", Tuple.of(vkId));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        Row row = rows.iterator().next();
        User user = mapRow(row, User.class);
        if (user != null && user.getId() == null) {
            user.setId(row.getLong("id"));
        }
        return Optional.ofNullable(user);
    }

    @Override
    public List<User> findAll() {
        RowSet<Row> rows = execute("SELECT id, data FROM users");
        List<User> result = new ArrayList<>();
        for (Row row : rows) {
            User user = mapRow(row, User.class);
            if (user != null && user.getId() == null) {
                user.setId(row.getLong("id"));
            }
            if (user != null) {
                result.add(user);
            }
        }
        return result;
    }

    @Override
    public List<User> findAll(int page, int limit) {
        List<User> allUsers = findAll().stream()
            .sorted((u1, u2) -> {
                LocalDateTime date1 = u1.getUpdatedAtDateTime();
                LocalDateTime date2 = u2.getUpdatedAtDateTime();
                if (date1 == null && date2 == null) return 0;
                if (date1 == null) return 1;
                if (date2 == null) return -1;
                return date2.compareTo(date1);
            })
            .collect(Collectors.toList());

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, allUsers.size());
        if (start >= allUsers.size()) {
            return new ArrayList<>();
        }
        return allUsers.subList(start, end);
    }

    @Override
    public List<User> findOnlineUsers() {
        return findAll().stream()
            .filter(user -> Boolean.TRUE.equals(user.getIsOnline()))
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findByGender(User.Gender gender) {
        return findAll().stream()
            .filter(user -> gender.equals(user.getGenderEnum()))
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findByAgeRange(Integer minAge, Integer maxAge) {
        return findAll().stream()
            .filter(user -> {
                Integer age = resolveAge(user);
                return age != null && age >= minAge && age <= maxAge;
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findByCity(String city) {
        return findAll().stream()
            .filter(user -> city.equals(user.getCity()))
            .collect(Collectors.toList());
    }

    @Override
    public List<User> findForMatching(User.Gender gender, Integer minAge, Integer maxAge, String city) {
        return findAll().stream()
            .filter(user -> Boolean.TRUE.equals(user.getIsVisible()))
            .filter(user -> {
                if (user.getSettings() == null) {
                    return false;
                }
                return Boolean.TRUE.equals(user.getSettings().getAllowMessages());
            })
            .filter(user -> gender == null || gender.equals(user.getGenderEnum()))
            .filter(user -> {
                Integer age = resolveAge(user);
                return age == null || (age >= minAge && age <= maxAge);
            })
            .filter(user -> city == null || city.equals(user.getCity()))
            .collect(Collectors.toList());
    }

    @Override
    public void updateOnlineStatus(Long userId, boolean isOnline) {
        findById(userId).ifPresent(user -> {
            user.updateOnlineStatus(isOnline);
            save(user);
        });
    }

    @Override
    public void updateBalance(Long userId, Integer balance) {
        findById(userId).ifPresent(user -> {
            user.setBalance(balance);
            user.setUpdatedAtDateTime(LocalDateTime.now());
            save(user);
        });
    }

    @Override
    public void deleteById(Long id) {
        execute("DELETE FROM users WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(Long id) {
        RowSet<Row> rows = execute("SELECT 1 FROM users WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM users");
        Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
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
}
