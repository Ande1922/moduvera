package io.github.ande1922.moduvera.reference.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.authorization.PermissionDeniedException;
import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.catalog.api.GetProductQuery;
import io.github.ande1922.moduvera.reference.catalog.domain.Product;
import io.github.ande1922.moduvera.reference.catalog.infrastructure.memory.InMemoryProductRepository;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.context.TenantId;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogApplicationServiceTest {

    @Test
    void readsOnlyTheCurrentTenantsAuthoritativeSnapshot() {
        var repository = new InMemoryProductRepository();
        ExecutionContextHolder.run(context("tenant-a", true), () -> repository.save(new Product(
                7, "Coffee", new BigDecimal("18.00"), Currency.getInstance("CNY"), 3)));
        ExecutionContextHolder.run(context("tenant-b", true), () -> repository.save(new Product(
                7, "Tea", new BigDecimal("12.00"), Currency.getInstance("CNY"), 2)));
        var service = new CatalogApplicationService(repository, new UseCaseAuthorizer());

        var tenantASnapshot =
                ExecutionContextHolder.call(context("tenant-a", true), () -> service.getProduct(new GetProductQuery(7)));
        var tenantBSnapshot =
                ExecutionContextHolder.call(context("tenant-b", true), () -> service.getProduct(new GetProductQuery(7)));

        assertThat(tenantASnapshot.name()).isEqualTo("Coffee");
        assertThat(tenantASnapshot.version()).isEqualTo(3);
        assertThat(tenantBSnapshot.name()).isEqualTo("Tea");
        assertThat(tenantBSnapshot.version()).isEqualTo(2);
        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        context("tenant-a", false), () -> service.getProduct(new GetProductQuery(7))))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> repository.findById(7))
                .isInstanceOf(MissingExecutionContextException.class);
    }

    private static ExecutionContext context(String tenant, boolean permitted) {
        Set<String> permissions = permitted ? Set.of(CatalogApplicationService.READ_PRODUCT.value()) : Set.of();
        Actor actor = new Actor(ActorType.USER, "alice", permissions);
        return ExecutionContext.initiatedBy(new TenantId(tenant), actor, "corr-catalog");
    }
}
