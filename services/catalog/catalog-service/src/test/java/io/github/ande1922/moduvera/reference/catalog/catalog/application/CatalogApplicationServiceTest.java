package io.github.ande1922.moduvera.reference.catalog.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.authorization.PermissionDeniedException;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import io.github.ande1922.moduvera.reference.catalog.api.ProductSnapshot;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.Product;
import io.github.ande1922.moduvera.reference.catalog.catalog.domain.ProductRepository;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogApplicationServiceTest {

    @Test
    void authorizesBeforeReadingTheRepository() {
        ProductRepository repository = new ProductRepository() {
            @Override
            public Optional<Product> findById(long productId) {
                throw new AssertionError("repository must not be read before authorization");
            }

            @Override
            public void save(Product product) {
                throw new UnsupportedOperationException("application stub does not provide persistence");
            }
        };
        var service = new CatalogApplicationService(repository, new UseCaseAuthorizer());

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        context(false), () -> service.getProduct(new GetProductQuery(7))))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void reportsNotFoundWhenTheRepositoryHasNoProduct() {
        var service = new CatalogApplicationService(
                new ReadOnlyProductRepository(Optional.empty()), new UseCaseAuthorizer());

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        context(true), () -> service.getProduct(new GetProductQuery(7))))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("7");
    }

    @Test
    void mapsTheRepositoryProductToThePublicSnapshot() {
        var product = new Product(
                7, "Coffee", new BigDecimal("18.00"), Currency.getInstance("CNY"), 3);
        var service = new CatalogApplicationService(
                new ReadOnlyProductRepository(Optional.of(product)), new UseCaseAuthorizer());

        var snapshot = ExecutionContextHolder.call(
                context(true), () -> service.getProduct(new GetProductQuery(7)));

        assertThat(snapshot)
                .isEqualTo(new ProductSnapshot(
                        7, "Coffee", new BigDecimal("18.00"), Currency.getInstance("CNY"), 3));
    }

    private static ExecutionContext context(boolean permitted) {
        Set<String> permissions = permitted ? Set.of(CatalogApplicationService.READ_PRODUCT.value()) : Set.of();
        Actor actor = new Actor(ActorType.USER, "alice", permissions);
        return ExecutionContext.initiatedBy(new TenantId("tenant-a"), actor, "corr-catalog");
    }

    private record ReadOnlyProductRepository(Optional<Product> result) implements ProductRepository {

        @Override
        public Optional<Product> findById(long productId) {
            return result;
        }

        @Override
        public void save(Product product) {
            throw new UnsupportedOperationException("application stub does not provide persistence");
        }
    }
}
