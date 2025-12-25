package com.tindapp.config;

import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;

public class DatabaseConfig {

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final boolean ssl;
    private final int maxPoolSize;

    private DatabaseConfig(
        final boolean enabled,
        final String host,
        final int port,
        final String database,
        final String user,
        final String password,
        final boolean ssl,
        final int maxPoolSize
    ) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.ssl = ssl;
        this.maxPoolSize = maxPoolSize;
    }

    public static DatabaseConfig fromEnvironment() {
        final boolean enabled = getEnvBool("DB_ENABLED", true);
        final String host = System.getenv("DB_HOST");
        final int port = getEnvInt("DB_PORT", 5432);
        final String database = System.getenv("DB_NAME");
        final String user = System.getenv("DB_USER");
        final String password = System.getenv("DB_PASSWORD");
        final boolean ssl = getEnvBool("DB_SSL", false);
        final int maxPoolSize = getEnvInt("DB_POOL_SIZE", 8);

        return new DatabaseConfig(enabled, host, port, database, user, password, ssl, maxPoolSize);
    }

    public boolean isEnabled() {
        return enabled && database != null && !database.isBlank();
    }

    public PgConnectOptions toConnectOptions() {
        return new PgConnectOptions()
            .setPort(port)
            .setHost(host)
            .setDatabase(database)
            .setUser(user)
            .setPassword(password)
            .setSsl(ssl);
    }

    public PoolOptions toPoolOptions() {
        return new PoolOptions()
            .setMaxSize(maxPoolSize);
    }

    public String getSafeDescription() {
        return String.format("%s:%d/%s (user: %s, ssl: %s)",
            host, port, database, user, ssl);
    }

    private static String getEnv(final String key, final String defaultValue) {
        final String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static boolean getEnvBool(final String key, final boolean defaultValue) {
        final String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private static int getEnvInt(final String key, final int defaultValue) {
        final String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }
}
