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
        boolean enabled,
        String host,
        int port,
        String database,
        String user,
        String password,
        boolean ssl,
        int maxPoolSize
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
        boolean enabled = getEnvBool("DB_ENABLED", true);
        String host = System.getenv("DB_HOST");
        int port = getEnvInt("DB_PORT", 5432);
        String database = System.getenv("DB_NAME");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");
        boolean ssl = getEnvBool("DB_SSL", false);
        int maxPoolSize = getEnvInt("DB_POOL_SIZE", 8);

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

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static boolean getEnvBool(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private static int getEnvInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
