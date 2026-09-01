package io.github.ande1922.moduvera.reference.app.catalog;

import io.github.ande1922.moduvera.reference.catalog.catalog.CatalogModuleConfiguration;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogInternalHttpInboundConfiguration;
import io.github.ande1922.moduvera.reference.catalog.catalog.adapter.outbound.persistence.CatalogPersistenceOutboundConfiguration;
import io.github.ande1922.moduvera.reference.catalog.migration.CatalogMigrationConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    CatalogModuleConfiguration.class,
    CatalogInternalHttpInboundConfiguration.class,
    CatalogPersistenceOutboundConfiguration.class,
    CatalogMigrationConfiguration.class
})
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
