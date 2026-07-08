package com.tindapp.repository.postgres;

import com.tindapp.model.Subscription;
import com.tindapp.repository.SubscriptionRepository;
import io.vertx.core.Future;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PostgresSubscriptionRepository extends AbstractPostgresRepository implements SubscriptionRepository {

    private static final int MAX_LIMIT = 100;
    private static final String SUBSCRIPTION_COLUMNS = """
        id, user_id, type, status, start_date, end_date, price, payment_method, auto_renew, plan_id,
        vk_subscription_id, price_in_votes, next_bill_date, pending_cancel, cancel_reason, app_order_id
        """;

    public PostgresSubscriptionRepository(final PgPool client) {
        super(client);
    }

    @Override
    public Future<Subscription> save(final Subscription subscription) {
        if (subscription == null) {
            return Future.failedFuture(new IllegalArgumentException("Subscription is null"));
        }
        if (subscription.getId() == null || subscription.getId().isBlank()) {
            subscription.setId(UUID.randomUUID().toString());
        }
        if (subscription.getStatus() == null) {
            subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        }
        if (subscription.getStartDate() == null) {
            subscription.setStartDate(LocalDateTime.now());
        }
        if (subscription.getAutoRenew() == null) {
            subscription.setAutoRenew(false);
        }
        if (subscription.getPendingCancel() == null) {
            subscription.setPendingCancel(false);
        }

        return execute("""
            INSERT INTO subscriptions (
                id, user_id, type, status, start_date, end_date, price, payment_method, auto_renew,
                plan_id, vk_subscription_id, price_in_votes, next_bill_date, pending_cancel, cancel_reason, app_order_id
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)
            ON CONFLICT (id) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                type = EXCLUDED.type,
                status = EXCLUDED.status,
                start_date = EXCLUDED.start_date,
                end_date = EXCLUDED.end_date,
                price = EXCLUDED.price,
                payment_method = EXCLUDED.payment_method,
                auto_renew = EXCLUDED.auto_renew,
                plan_id = EXCLUDED.plan_id,
                vk_subscription_id = EXCLUDED.vk_subscription_id,
                price_in_votes = EXCLUDED.price_in_votes,
                next_bill_date = EXCLUDED.next_bill_date,
                pending_cancel = EXCLUDED.pending_cancel,
                cancel_reason = EXCLUDED.cancel_reason,
                app_order_id = EXCLUDED.app_order_id
            """, Tuple.of(
            subscription.getId(),
            subscription.getUserId(),
            subscription.getType() != null ? subscription.getType().name() : null,
            subscription.getStatus().name(),
            toOffset(subscription.getStartDate()),
            toOffset(subscription.getEndDate()),
            subscription.getPrice(),
            subscription.getPaymentMethod() != null ? subscription.getPaymentMethod().name() : null,
            subscription.getAutoRenew(),
            subscription.getPlanId(),
            subscription.getVkSubscriptionId(),
            subscription.getPriceInVotes(),
            toOffset(subscription.getNextBillDate()),
            subscription.getPendingCancel(),
            subscription.getCancelReason(),
            subscription.getAppOrderId()
        )).map(subscription);
    }

    @Override
    public Future<Optional<Subscription>> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional(
            "SELECT " + SUBSCRIPTION_COLUMNS + " FROM subscriptions WHERE id = $1 LIMIT 1",
            Tuple.of(id),
            this::mapSubscription
        );
    }

    @Override
    public Future<List<Subscription>> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryList(
            "SELECT " + SUBSCRIPTION_COLUMNS + " FROM subscriptions ORDER BY start_date DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit)),
            this::mapSubscription
        );
    }

    @Override
    public Future<Optional<Subscription>> findActiveByUserId(final Long userId) {
        if (userId == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional(
            "SELECT " + SUBSCRIPTION_COLUMNS + " FROM subscriptions WHERE user_id = $1 AND status = 'ACTIVE' AND (end_date IS NULL OR end_date > NOW()) ORDER BY start_date DESC LIMIT 1",
            Tuple.of(userId),
            this::mapSubscription
        );
    }

    @Override
    public Future<List<Subscription>> findExpiring() {
        return queryList(
            "SELECT " + SUBSCRIPTION_COLUMNS + " FROM subscriptions WHERE status = 'ACTIVE' AND end_date IS NOT NULL AND end_date < NOW() + INTERVAL '1 day' ORDER BY end_date ASC",
            Tuple.tuple(),
            this::mapSubscription
        );
    }

    @Override
    public Future<Void> cancelByUserId(final Long userId) {
        return execute("""
            UPDATE subscriptions
            SET status = 'CANCELLED',
                auto_renew = FALSE,
                pending_cancel = FALSE,
                end_date = NOW(),
                next_bill_date = NULL
            WHERE user_id = $1 AND status = 'ACTIVE'
            """, Tuple.of(userId)).mapEmpty();
    }

    @Override
    public Future<Void> expireById(final String subscriptionId) {
        return execute("""
            UPDATE subscriptions
            SET status = 'EXPIRED',
                auto_renew = FALSE,
                pending_cancel = FALSE
            WHERE id = $1
            """, Tuple.of(subscriptionId)).mapEmpty();
    }

    @Override
    public Future<Boolean> hasActiveSubscription(final Long userId) {
        if (userId == null) {
            return Future.succeededFuture(false);
        }
        return exists(
            "SELECT 1 FROM subscriptions WHERE user_id = $1 AND status = 'ACTIVE' AND (end_date IS NULL OR end_date > NOW()) LIMIT 1",
            Tuple.of(userId)
        );
    }

    @Override
    public Future<Optional<Subscription>> findByVkSubscriptionId(final String vkSubscriptionId) {
        if (vkSubscriptionId == null || vkSubscriptionId.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional(
            "SELECT " + SUBSCRIPTION_COLUMNS + " FROM subscriptions WHERE vk_subscription_id = $1 LIMIT 1",
            Tuple.of(vkSubscriptionId),
            this::mapSubscription
        );
    }

    @Override
    public Future<Void> cancelByVkSubscriptionId(final String vkSubscriptionId) {
        return execute("""
            UPDATE subscriptions
            SET status = 'CANCELLED',
                auto_renew = FALSE,
                pending_cancel = FALSE,
                end_date = NOW(),
                next_bill_date = NULL
            WHERE vk_subscription_id = $1
            """, Tuple.of(vkSubscriptionId)).mapEmpty();
    }

    @Override
    public Future<Long> countActiveSubscriptions() {
        return countRows("SELECT COUNT(*) AS cnt FROM subscriptions WHERE status = 'ACTIVE' AND (end_date IS NULL OR end_date > NOW())");
    }

    @Override
    public Future<Void> deleteById(final String id) {
        return execute("DELETE FROM subscriptions WHERE id = $1", Tuple.of(id)).mapEmpty();
    }

    @Override
    public Future<Boolean> existsById(final String id) {
        if (id == null || id.isBlank()) {
            return Future.succeededFuture(false);
        }
        return exists("SELECT 1 FROM subscriptions WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public Future<Long> count() {
        return countRows("SELECT COUNT(*) AS cnt FROM subscriptions");
    }

    private Subscription mapSubscription(final Row row) {
        if (row == null) {
            return null;
        }
        final Subscription subscription = new Subscription();
        subscription.setId(row.getString("id"));
        subscription.setUserId(row.getLong("user_id"));

        final String type = row.getString("type");
        if (type != null) {
            subscription.setType(Subscription.SubscriptionType.valueOf(type));
        }

        final String status = row.getString("status");
        if (status != null) {
            subscription.setStatus(Subscription.SubscriptionStatus.valueOf(status));
        }

        subscription.setStartDate(toLocalDateTime(row.getOffsetDateTime("start_date")));
        subscription.setEndDate(toLocalDateTime(row.getOffsetDateTime("end_date")));
        subscription.setPrice(row.getDouble("price"));

        final String paymentMethod = row.getString("payment_method");
        if (paymentMethod != null) {
            subscription.setPaymentMethod(Subscription.PaymentMethod.valueOf(paymentMethod));
        }

        subscription.setAutoRenew(row.getBoolean("auto_renew"));
        subscription.setPlanId(row.getString("plan_id"));
        subscription.setVkSubscriptionId(row.getString("vk_subscription_id"));
        subscription.setPriceInVotes(row.getInteger("price_in_votes"));
        subscription.setNextBillDate(toLocalDateTime(row.getOffsetDateTime("next_bill_date")));
        subscription.setPendingCancel(row.getBoolean("pending_cancel"));
        subscription.setCancelReason(row.getString("cancel_reason"));
        subscription.setAppOrderId(row.getInteger("app_order_id"));
        return subscription;
    }

    private LocalDateTime toLocalDateTime(final OffsetDateTime value) {
        return value != null ? value.toLocalDateTime() : null;
    }
}
