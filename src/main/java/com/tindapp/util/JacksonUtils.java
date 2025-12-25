package com.tindapp.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.json.JsonObject;

public final class JacksonUtils {

    private static final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JacksonUtils() {
    }

    public static ObjectMapper mapper() {
        return mapper;
    }

    public static <T> JsonObject toJsonObject(final T entity) {
        try {
            return new JsonObject(mapper.writeValueAsString(entity));
        } catch (final Exception e) {
            throw new RuntimeException("Failed to serialize entity to JSON", e);
        }
    }

    public static <T> T fromJson(final JsonObject json, final Class<T> type) {
        try {
            return mapper.readValue(json.encode(), type);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to deserialize JSON to " + type.getSimpleName(), e);
        }
    }
}
