package com.tindapp.repository.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tindapp.util.JacksonUtils;
import com.tindapp.util.DateTimeUtils;
import io.vertx.core.Context;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

public abstract class AbstractPostgresRepository {

    protected final PgPool client;

    protected AbstractPostgresRepository(final PgPool client) {
        this.client = client;
    }

    protected RowSet<Row> execute(final String sql) {
        return execute(sql, Tuple.tuple());
    }

    protected RowSet<Row> execute(final String sql, final Tuple params) {
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("Blocking database access from Vert.x event loop is forbidden");
        }
        try {
            return client.preparedQuery(sql)
                .execute(params == null ? Tuple.tuple() : params)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        } catch (final Exception e) {
            throw new RuntimeException("Database query failed: " + e.getMessage(), e);
        }
    }

    protected Optional<Row> firstRow(final String sql, final Tuple params) {
        return firstRow(execute(sql, params));
    }

    protected Optional<Row> firstRow(final String sql) {
        return firstRow(execute(sql));
    }

    protected Optional<Row> firstRow(final RowSet<Row> rows) {
        if (rows == null) {
            return Optional.empty();
        }
        final java.util.Iterator<Row> iterator = rows.iterator();
        return iterator.hasNext() ? Optional.ofNullable(iterator.next()) : Optional.empty();
    }

    protected boolean exists(final String sql, final Tuple params) {
        return firstRow(sql, params).isPresent();
    }

    protected boolean exists(final String sql) {
        return firstRow(sql).isPresent();
    }

    protected long countRows(final String sql, final Tuple params) {
        return firstRow(sql, params)
            .map(row -> row.getLong("cnt"))
            .orElse(0L);
    }

    protected long countRows(final String sql) {
        return firstRow(sql)
            .map(row -> row.getLong("cnt"))
            .orElse(0L);
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
