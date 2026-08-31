package io.github.ande1922.moduvera.reference.catalog;

import io.github.ande1922.moduvera.authorization.PermissionDeniedException;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.application.ProductNotFoundException;
import io.github.ande1922.moduvera.reference.catalog.inbound.http.CatalogHttpController;
import io.github.ande1922.moduvera.web.ProblemStatusContributor;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration(proxyBeanMethods = false)
public class CatalogInternalHttpConfiguration {

    @Bean
    CatalogHttpController catalogHttpController(CatalogApi catalog) {
        return new CatalogHttpController(catalog);
    }

    @Bean
    ProblemStatusContributor catalogProblemStatusContributor() {
        return exception -> {
            if (exception instanceof ProductNotFoundException) {
                return Optional.of(HttpStatus.NOT_FOUND);
            }
            if (exception instanceof PermissionDeniedException) {
                return Optional.of(HttpStatus.FORBIDDEN);
            }
            return Optional.empty();
        };
    }
}
