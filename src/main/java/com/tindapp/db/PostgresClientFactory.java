package com.tindapp.db;

import com.tindapp.config.DatabaseConfig;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(PostgresClientFactory.class);

    private PostgresClientFactory() {
    }

    public static Future<PgPool> createPool(final Vertx vertx, final DatabaseConfig config) {
        if (vertx == null || config == null || !config.isEnabled()) {
            throw new IllegalArgumentException("PostgreSQL configuration is invalid");
        }

        try {
            final PgPool pool = PgPool.pool(vertx, config.toConnectOptions(), config.toPoolOptions());
            final Promise<PgPool> promise = Promise.promise();
            pool.query("SELECT 1").execute(ar -> {
                if (ar.succeeded()) {
                    logger.info("Postgres pool is ready for {}", config.getSafeDescription());
                    promise.complete(pool);
                    return;
                }

                final Throwable cause = ar.cause();
                pool.close(closeAr -> {
                    logger.error("Failed to create Postgres pool for {}", config.getSafeDescription(), cause);
                    promise.fail(new IllegalStateException("Failed to initialize PostgreSQL connection pool", cause));
                });
            });
            return promise.future();
        } catch (final Exception e) {
            logger.error("Failed to create Postgres pool for {}", config.getSafeDescription(), e);
            return Future.failedFuture(new IllegalStateException("Failed to initialize PostgreSQL connection pool", e));
        }
    }
}
