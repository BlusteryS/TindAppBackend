package com.tindapp.repository.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tindapp.util.DateTimeUtils;
import com.tindapp.util.JacksonUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public abstract class AbstractPostgresRepository {

    protected final PgPool client;

    protected AbstractPostgresRepository(final PgPool client) {
        this.client = client;
    }

    protected Future<RowSet<Row>> execute(final String sql) {
        return execute(sql, Tuple.tuple());
    }

    protected Future<RowSet<Row>> execute(final String sql, final Tuple params) {
        final Promise<RowSet<Row>> promise = Promise.promise();
        client.preparedQuery(sql)
            .execute(params == null ? Tuple.tuple() : params, ar -> {
                if (ar.succeeded()) {
                    promise.complete(ar.result());
                } else {
                    promise.fail(new RuntimeException("Database query failed: " + ar.cause().getMessage(), ar.cause()));
                }
            });
        return promise.future();
    }

    protected Future<Optional<Row>> firstRow(final String sql, final Tuple params) {
        return execute(sql, params).map(this::firstRow);
    }

    protected Future<Optional<Row>> firstRow(final String sql) {
        return execute(sql).map(this::firstRow);
    }

    protected Optional<Row> firstRow(final RowSet<Row> rows) {
        if (rows == null) {
            return Optional.empty();
        }
        final java.util.Iterator<Row> iterator = rows.iterator();
        return iterator.hasNext() ? Optional.ofNullable(iterator.next()) : Optional.empty();
    }

    protected Future<Boolean> exists(final String sql, final Tuple params) {
        return firstRow(sql, params).map(Optional::isPresent);
    }

    protected Future<Boolean> exists(final String sql) {
        return firstRow(sql).map(Optional::isPresent);
    }

    protected Future<Long> countRows(final String sql, final Tuple params) {
        return firstRow(sql, params)
            .map(row -> row.map(value -> value.getLong("cnt")).orElse(0L));
    }

    protected Future<Long> countRows(final String sql) {
        return firstRow(sql)
            .map(row -> row.map(value -> value.getLong("cnt")).orElse(0L));
    }

    protected <T> Future<List<T>> queryList(final String sql, final Tuple params, final Function<Row, T> mapper) {
        return execute(sql, params).map(rows -> {
            final List<T> items = new ArrayList<>();
            for (final Row row : rows) {
                final T item = mapper.apply(row);
                if (item != null) {
                    items.add(item);
                }
            }
            return items;
        });
    }

    protected <T> Future<List<T>> queryList(final String sql, final Function<Row, T> mapper) {
        return queryList(sql, Tuple.tuple(), mapper);
    }

    protected <T> Future<Optional<T>> queryOptional(final String sql, final Tuple params, final Function<Row, T> mapper) {
        return firstRow(sql, params).map(row -> row.map(mapper));
    }

    protected <T> Future<Optional<T>> queryOptional(final String sql, final Function<Row, T> mapper) {
        return firstRow(sql).map(row -> row.map(mapper));
    }

    protected <T> JsonObject toJson(final T entity) {
        return JacksonUtils.toJsonObject(entity);
    }

    protected <T> T fromJsonObject(final Object value, final Class<T> type) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonObject jsonObject) {
            return JacksonUtils.mapper().convertValue(jsonObject.getMap(), type);
        }
        return JacksonUtils.mapper().convertValue(value, type);
    }

    protected <T> T fromJsonValue(final Object value, final TypeReference<T> typeReference) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonObject jsonObject) {
            return JacksonUtils.mapper().convertValue(jsonObject.getMap(), typeReference);
        }
        if (value instanceof JsonArray jsonArray) {
            return JacksonUtils.mapper().convertValue(jsonArray.getList(), typeReference);
        }
        return JacksonUtils.mapper().convertValue(value, typeReference);
    }

    protected OffsetDateTime toOffset(final LocalDateTime time) {
        return time != null ? time.atOffset(ZoneOffset.UTC) : null;
    }

    protected OffsetDateTime toOffset(final String isoDateTime) {
        final LocalDateTime dateTime = DateTimeUtils.parseFromIso(isoDateTime);
        return toOffset(dateTime);
    }

    protected String toIso(final OffsetDateTime offsetDateTime) {
        return offsetDateTime != null ? DateTimeUtils.formatToIso(offsetDateTime.toLocalDateTime()) : null;
    }

    protected int safePage(final int page) {
        return Math.max(page, 1);
    }

    protected int safeLimit(final int limit, final int maxLimit) {
        return Math.min(Math.max(limit, 1), maxLimit);
    }

    protected int offset(final int page, final int limit) {
        return (safePage(page) - 1) * limit;
    }
}
