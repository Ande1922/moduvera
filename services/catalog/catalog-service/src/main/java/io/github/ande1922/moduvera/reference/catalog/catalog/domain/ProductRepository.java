package io.github.ande1922.moduvera.reference.catalog.catalog.domain;

import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findById(long productId);

    void save(Product product);
}
