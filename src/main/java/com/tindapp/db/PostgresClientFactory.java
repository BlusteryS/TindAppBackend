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
            throw new IllegalArgumentException("PostgreSQL configuration is invalid");
        }

        try {
            final PgPool pool = PgPool.pool(vertx, config.toConnectOptions(), config.toPoolOptions());
            try {
                pool.query("SELECT 1")
                    .execute()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            } catch (final Exception e) {
                pool.close()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
                throw e;
            }
            logger.info("Postgres pool is ready for {}", config.getSafeDescription());
            return pool;
        } catch (final Exception e) {
            logger.error("Failed to create Postgres pool for {}", config.getSafeDescription(), e);
            throw new IllegalStateException("Failed to initialize PostgreSQL connection pool", e);
        }
    }
}
