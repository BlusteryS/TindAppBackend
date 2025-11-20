package com.tindapp.repository.postgres;

import com.tindapp.model.Subscription;
import com.tindapp.repository.SubscriptionRepository;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class PostgresSubscriptionRepository extends AbstractPostgresRepository implements SubscriptionRepository {

    private static final Comparator<Subscription> START_DATE_DESC = Comparator
        .comparing(Subscription::getStartDate, Comparator.nullsLast(Comparator.naturalOrder()))
        .reversed();

    public PostgresSubscriptionRepository(PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS subscriptions (
                id TEXT PRIMARY KEY,
                data JSONB NOT NULL
            )
            """);
    }

    @Override
    public Subscription save(Subscription subscription) {
        if (subscription == null) {
            throw new IllegalArgumentException("Subscription is null");
        }
        if (subscription.getId() == null) {
            subscription.setId(UUID.randomUUID().toString());
        }
        JsonObject payload = toJson(subscription);
        execute(
            "INSERT INTO subscriptions (id, data) VALUES ($1, $2::jsonb) " +
                "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            Tuple.of(subscription.getId(), payload)
        );
        return subscription;
    }

    @Override
    public Optional<Subscription> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        RowSet<Row> rows = execute("SELECT data FROM subscriptions WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapRow(rows.iterator().next(), Subscription.class));
    }

    @Override
    public List<Subscription> findAll() {
        RowSet<Row> rows = execute("SELECT data FROM subscriptions");
        List<Subscription> result = new ArrayList<>();
        for (Row row : rows) {
            Subscription subscription = mapRow(row, Subscription.class);
            if (subscription != null) {
                result.add(subscription);
            }
        }
        return result;
    }

    @Override
    public List<Subscription> findAll(int page, int limit) {
        List<Subscription> allSubs = findAll().stream()
            .sorted(START_DATE_DESC)
            .collect(Collectors.toList());
        int start = (page - 1) * limit;
        int end = Math.min(start + limit, allSubs.size());
        if (start >= allSubs.size()) {
            return new ArrayList<>();
        }
        return allSubs.subList(start, end);
    }

    @Override
    public Optional<Subscription> findActiveByUserId(Long userId) {
        return findAll().stream()
            .filter(sub -> userId.equals(sub.getUserId()))
            .filter(Subscription::isActive)
            .findFirst();
    }

    @Override
    public List<Subscription> findByUserId(Long userId) {
        return findAll().stream()
            .filter(sub -> userId.equals(sub.getUserId()))
            .sorted(START_DATE_DESC)
            .collect(Collectors.toList());
    }

    @Override
    public List<Subscription> findByStatus(Subscription.SubscriptionStatus status) {
        return findAll().stream()
            .filter(sub -> status.equals(sub.getStatus()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Subscription> findByType(Subscription.SubscriptionType type) {
        return findAll().stream()
            .filter(sub -> type.equals(sub.getType()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Subscription> findExpiring() {
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);
        return findAll().stream()
            .filter(sub -> sub.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
            .filter(sub -> sub.getEndDate() != null && sub.getEndDate().isBefore(tomorrow))
            .collect(Collectors.toList());
    }

    @Override
    public void cancelByUserId(Long userId) {
        findByUserId(userId).stream()
            .filter(sub -> sub.getStatus() == Subscription.SubscriptionStatus.ACTIVE)
            .forEach(sub -> {
                sub.cancel();
                save(sub);
            });
    }

    @Override
    public void expireById(String subscriptionId) {
        findById(subscriptionId).ifPresent(sub -> {
            sub.expire();
            save(sub);
        });
    }

    @Override
    public boolean hasActiveSubscription(Long userId) {
        return findActiveByUserId(userId).isPresent();
    }

    @Override
    public Optional<Subscription> findByVkSubscriptionId(String vkSubscriptionId) {
        return findAll().stream()
            .filter(sub -> vkSubscriptionId != null && vkSubscriptionId.equals(sub.getVkSubscriptionId()))
            .findFirst();
    }

    @Override
    public void cancelByVkSubscriptionId(String vkSubscriptionId) {
        findByVkSubscriptionId(vkSubscriptionId).ifPresent(sub -> {
            sub.cancel();
            save(sub);
        });
    }

    @Override
    public long countActiveSubscriptions() {
        return findAll().stream()
            .filter(Subscription::isActive)
            .count();
    }

    @Override
    public void deleteById(String id) {
        execute("DELETE FROM subscriptions WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(String id) {
        RowSet<Row> rows = execute("SELECT 1 FROM subscriptions WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM subscriptions");
        Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
    }
}
