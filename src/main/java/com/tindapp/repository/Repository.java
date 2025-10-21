package com.tindapp.repository;

import java.util.List;
import java.util.Optional;

public interface Repository<T, ID> {

    T save(T entity);

    Optional<T> findById(ID id);

    List<T> findAll();

    List<T> findAll(int page, int limit);

    void deleteById(ID id);

    boolean existsById(ID id);

    long count();
}
