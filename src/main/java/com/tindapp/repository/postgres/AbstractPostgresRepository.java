package com.tindapp.repository.postgres;

import com.tindapp.util.JacksonUtils;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

public abstract class AbstractPostgresRepository {

    protected final PgPool client;

    protected AbstractPostgresRepository(PgPool client) {
        this.client = client;
    }

    protected RowSet<Row> execute(String sql) {
        return execute(sql, Tuple.tuple());
    }

    protected RowSet<Row> execute(String sql, Tuple params) {
        try {
            return client.preparedQuery(sql)
                .execute(params == null ? Tuple.tuple() : params)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        } catch (Exception e) {
            throw new RuntimeException("Database query failed: " + e.getMessage(), e);
        }
    }

    protected void ensureTable(String ddl) {
        execute(ddl);
    }

    protected <T> JsonObject toJson(T entity) {
        return JacksonUtils.toJsonObject(entity);
    }

    protected <T> T mapRow(Row row, Class<T> type) {
        JsonObject json = row.getJsonObject("data");
        if (json == null) {
            return null;
        }
        return JacksonUtils.fromJson(json, type);
    }
}
