package com.gaopc.platform.catalog;

import com.gaopc.platform.authorization.PermissionDeniedException;
import com.gaopc.platform.catalog.api.CatalogApi;
import com.gaopc.platform.catalog.application.ProductNotFoundException;
import com.gaopc.platform.catalog.inbound.http.CatalogHttpController;
import com.gaopc.platform.web.ProblemStatusResolver;
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
    ProblemStatusResolver catalogProblemStatusResolver() {
        return exception -> {
            if (exception instanceof ProductNotFoundException) {
                return HttpStatus.NOT_FOUND;
            }
            if (exception instanceof PermissionDeniedException) {
                return HttpStatus.FORBIDDEN;
            }
            return HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }
}
