package io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.Product;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.ProductRepository;
import java.time.Clock;
import java.util.Currency;
import java.util.Optional;

public final class MybatisCatalogProductRepository implements ProductRepository {

    private final CatalogProductMapper mapper;
    private final Clock clock;

    public MybatisCatalogProductRepository(CatalogProductMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Optional<Product> findById(long productId) {
        String tenantId = ExecutionContextHolder.require().requireTenantId().value();
        return Optional.ofNullable(mapper.findById(tenantId, productId))
                .map(MybatisCatalogProductRepository::toDomain);
    }

    @Override
    public void save(Product product) {
        var context = ExecutionContextHolder.require();
        var now = clock.instant();
        var row = new CatalogProductRow();
        row.setTenantId(context.requireTenantId().value());
        row.setProductId(product.id());
        row.setName(product.name());
        row.setUnitPrice(product.price());
        row.setCurrency(product.currency().getCurrencyCode());
        row.setVersion(product.version());
        row.setCreatedAt(now);
        row.setCreatedBy(context.actor().subjectId());
        row.setUpdatedAt(now);
        row.setUpdatedBy(context.actor().subjectId());
        mapper.save(row);
    }

    private static Product toDomain(CatalogProductRow row) {
        return new Product(
                row.getProductId(),
                row.getName(),
                row.getUnitPrice(),
                Currency.getInstance(row.getCurrency()),
                row.getVersion());
    }
}
