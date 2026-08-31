package com.gaopc.platform.catalog;

import com.gaopc.platform.authorization.UseCaseAuthorizer;
import com.gaopc.platform.catalog.api.CatalogApi;
import com.gaopc.platform.catalog.application.CatalogApplicationService;
import com.gaopc.platform.catalog.domain.ProductRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogApplicationConfiguration {

    @Bean
    CatalogApi catalogApi(ProductRepository products, UseCaseAuthorizer authorizer) {
        return new CatalogApplicationService(products, authorizer);
    }
}
