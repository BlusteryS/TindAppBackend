package com.tindapp.db;

import com.tindapp.config.DatabaseConfig;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseMigrator {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrator.class);

    private DatabaseMigrator() {
    }

    public static void migrate(final DatabaseConfig config) {
        if (config == null || !config.isEnabled()) {
            throw new IllegalStateException("Database is not configured");
        }

        final Flyway flyway = Flyway.configure()
            .dataSource(config.getJdbcUrl(), config.getUser(), config.getPassword())
            .connectRetries(10)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .locations("classpath:db/migration")
            .load();

        final int migrations = flyway.migrate().migrationsExecuted;
        logger.info("Database migrations applied: {}", migrations);
    }
}
