package com.tindapp.util;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class FutureUtils {

    private FutureUtils() {
    }

    public static <T> Future<T> failed(final String message) {
        return Future.failedFuture(new RuntimeException(message));
    }

    public static <T> Future<T> requirePresent(final Future<Optional<T>> future, final String message) {
        return future.compose(optional -> optional
            .map(Future::succeededFuture)
            .orElseGet(() -> failed(message)));
    }

    public static Future<Void> all(final List<? extends Future<?>> futures) {
        if (futures == null || futures.isEmpty()) {
            return Future.succeededFuture();
        }
        return CompositeFuture.all(new ArrayList<>(futures)).mapEmpty();
    }

    public static <T, R> Future<List<R>> sequentialMap(final List<T> items, final Function<T, Future<R>> mapper) {
        Future<List<R>> chain = Future.succeededFuture(new ArrayList<>());
        if (items == null || items.isEmpty()) {
            return chain;
        }

        for (final T item : items) {
            chain = chain.compose(results ->
                mapper.apply(item).map(result -> {
                    results.add(result);
                    return results;
                })
            );
        }
        return chain;
    }
}
