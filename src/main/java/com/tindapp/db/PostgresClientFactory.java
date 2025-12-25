package com.tindapp.db;

import com.tindapp.config.DatabaseConfig;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(PostgresClientFactory.class);

    private PostgresClientFactory() {
    }

    public static PgPool createPool(final Vertx vertx, final DatabaseConfig config) {
        if (vertx == null || config == null || !config.isEnabled()) {
            return null;
        }

        try {
            return PgPool.pool(vertx, config.toConnectOptions(), config.toPoolOptions());
        } catch (final Exception e) {
            logger.error("Failed to create Postgres pool for {}", config.getSafeDescription(), e);
            return null;
        }
    }
}
