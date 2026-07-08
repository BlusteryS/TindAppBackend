package com.tindapp.repository;

import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;

public interface Repository<T, ID> {

    Future<T> save(T entity);

    Future<Optional<T>> findById(ID id);

    Future<List<T>> findAll(int page, int limit);

    Future<Void> deleteById(ID id);

    Future<Boolean> existsById(ID id);

    Future<Long> count();
}
