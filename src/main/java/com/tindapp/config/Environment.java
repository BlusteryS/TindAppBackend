package com.tindapp.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class Environment {

    private Environment() {
    }

    public static String require(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value.trim();
    }

    public static int requireInt(final String name) {
        try {
            return Integer.parseInt(require(name));
        } catch (final NumberFormatException error) {
            throw new IllegalStateException("Environment variable must be an integer: " + name, error);
        }
    }

    public static long requireLong(final String name) {
        try {
            return Long.parseLong(require(name));
        } catch (final NumberFormatException error) {
            throw new IllegalStateException("Environment variable must be a long integer: " + name, error);
        }
    }

    public static String[] requireList(final String name) {
        final String[] values = Arrays.stream(require(name).split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toArray(String[]::new);
        if (values.length == 0) {
            throw new IllegalStateException("Environment variable must contain at least one value: " + name);
        }
        return values;
    }

    public static Set<Long> requireLongSet(final String name) {
        try {
            return Arrays.stream(requireList(name))
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
        } catch (final NumberFormatException error) {
            throw new IllegalStateException("Environment variable must contain comma-separated integers: " + name, error);
        }
    }
}
