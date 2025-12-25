package com.tindapp.repository.postgres;

import com.tindapp.util.JacksonUtils;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

public abstract class AbstractPostgresRepository {

    protected final PgPool client;

    protected AbstractPostgresRepository(final PgPool client) {
        this.client = client;
    }

    protected RowSet<Row> execute(final String sql) {
        return execute(sql, Tuple.tuple());
    }

    protected RowSet<Row> execute(final String sql, final Tuple params) {
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

    protected void ensureTable(final String ddl) {
        execute(ddl);
    }

    protected <T> JsonObject toJson(final T entity) {
        return JacksonUtils.toJsonObject(entity);
    }

    protected <T> T mapRow(final Row row, final Class<T> type) {
        final JsonObject json = row.getJsonObject("data");
        if (json == null) {
            return null;
        }
        return JacksonUtils.fromJson(json, type);
    }
}
