package com.tindapp.config;

import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;

import java.util.concurrent.TimeUnit;

public class DatabaseConfig {

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final boolean ssl;
    private final int maxPoolSize;
    private final int connectTimeoutMs;
    private final int acquireTimeoutMs;
    private final int idleTimeoutSeconds;
    private final int poolCleanerPeriodMs;
    private final int maxWaitQueueSize;
    private final int reconnectAttempts;
    private final int reconnectIntervalMs;
    private final int pipeliningLimit;

    private DatabaseConfig(
        final boolean enabled,
        final String host,
        final int port,
        final String database,
        final String user,
        final String password,
        final boolean ssl,
        final int maxPoolSize,
        final int connectTimeoutMs,
        final int acquireTimeoutMs,
        final int idleTimeoutSeconds,
        final int poolCleanerPeriodMs,
        final int maxWaitQueueSize,
        final int reconnectAttempts,
        final int reconnectIntervalMs,
        final int pipeliningLimit
    ) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.ssl = ssl;
        this.maxPoolSize = maxPoolSize;
        this.connectTimeoutMs = connectTimeoutMs;
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.poolCleanerPeriodMs = poolCleanerPeriodMs;
        this.maxWaitQueueSize = maxWaitQueueSize;
        this.reconnectAttempts = reconnectAttempts;
        this.reconnectIntervalMs = reconnectIntervalMs;
        this.pipeliningLimit = pipeliningLimit;
    }

    public static DatabaseConfig fromEnvironment() {
        final boolean enabled = getEnvBool("DB_ENABLED", true);
        final String host = Environment.require("DB_HOST");
        final int port = getEnvInt("DB_PORT", 5432);
        final String database = Environment.require("DB_NAME");
        final String user = Environment.require("DB_USER");
        final String password = Environment.require("DB_PASSWORD");
        final boolean ssl = getEnvBool("DB_SSL", false);
        final int maxPoolSize = getEnvInt("DB_POOL_SIZE", 8);
        final int connectTimeoutMs = getEnvInt("DB_CONNECT_TIMEOUT_MS", 5000);
        final int acquireTimeoutMs = getEnvInt("DB_ACQUIRE_TIMEOUT_MS", 5000);
        final int idleTimeoutSeconds = getEnvInt("DB_IDLE_TIMEOUT_SECONDS", 300);
        final int poolCleanerPeriodMs = getEnvInt("DB_POOL_CLEANER_PERIOD_MS", 30_000);
        final int maxWaitQueueSize = getEnvInt("DB_MAX_WAIT_QUEUE_SIZE", 256);
        final int reconnectAttempts = getEnvInt("DB_RECONNECT_ATTEMPTS", 3);
        final int reconnectIntervalMs = getEnvInt("DB_RECONNECT_INTERVAL_MS", 1000);
        final int pipeliningLimit = getEnvInt("DB_PIPELINING_LIMIT", 8);

        return new DatabaseConfig(
            enabled,
            host,
            port,
            database,
            user,
            password,
            ssl,
            maxPoolSize,
            connectTimeoutMs,
            acquireTimeoutMs,
            idleTimeoutSeconds,
            poolCleanerPeriodMs,
            maxWaitQueueSize,
            reconnectAttempts,
            reconnectIntervalMs,
            pipeliningLimit
        );
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
            .setSsl(ssl)
            .setConnectTimeout(connectTimeoutMs)
            .setReconnectAttempts(reconnectAttempts)
            .setReconnectInterval(reconnectIntervalMs)
            .setTcpKeepAlive(true)
            .setTcpNoDelay(true)
            .setCachePreparedStatements(true)
            .setPipeliningLimit(Math.max(pipeliningLimit, 1));
    }

    public PoolOptions toPoolOptions() {
        return new PoolOptions()
            .setMaxSize(maxPoolSize)
            .setConnectionTimeout(acquireTimeoutMs)
            .setConnectionTimeoutUnit(TimeUnit.MILLISECONDS)
            .setIdleTimeout(idleTimeoutSeconds)
            .setIdleTimeoutUnit(TimeUnit.SECONDS)
            .setPoolCleanerPeriod(poolCleanerPeriodMs)
            .setMaxWaitQueueSize(maxWaitQueueSize);
    }

    public String getJdbcUrl() {
        final String sslMode = ssl ? "?sslmode=require" : "";
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + sslMode;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getSafeDescription() {
        return String.format("%s:%d/%s (user: %s, ssl: %s, pool: %d)",
            host, port, database, user, ssl, maxPoolSize);
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
