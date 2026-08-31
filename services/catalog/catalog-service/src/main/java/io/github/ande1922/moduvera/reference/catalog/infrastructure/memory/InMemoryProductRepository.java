package io.github.ande1922.moduvera.reference.catalog.infrastructure.memory;

import io.github.ande1922.moduvera.reference.catalog.domain.Product;
import io.github.ande1922.moduvera.reference.catalog.domain.ProductRepository;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryProductRepository implements ProductRepository {

    private final ConcurrentMap<Key, Product> products = new ConcurrentHashMap<>();

    @Override
    public Optional<Product> findById(long productId) {
        return Optional.ofNullable(products.get(new Key(currentTenant(), productId)));
    }

    @Override
    public void save(Product product) {
        products.put(new Key(currentTenant(), product.id()), product);
    }

    private static TenantId currentTenant() {
        return ExecutionContextHolder.require().tenantId();
    }

    private record Key(TenantId tenantId, long productId) {}
}
