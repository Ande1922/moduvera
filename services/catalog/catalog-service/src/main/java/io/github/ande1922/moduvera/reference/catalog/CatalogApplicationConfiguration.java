package io.github.ande1922.moduvera.reference.catalog;

import io.github.ande1922.moduvera.authorization.UseCaseAuthorizer;
import io.github.ande1922.moduvera.reference.catalog.api.CatalogApi;
import io.github.ande1922.moduvera.reference.catalog.application.CatalogApplicationService;
import io.github.ande1922.moduvera.reference.catalog.domain.ProductRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogApplicationConfiguration {

    @Bean
    CatalogApi catalogApi(ProductRepository products, UseCaseAuthorizer authorizer) {
        return new CatalogApplicationService(products, authorizer);
    }
}
