package io.github.ande1922.moduvera.reference.app.catalog;

import io.github.ande1922.moduvera.reference.catalog.CatalogApplicationConfiguration;
import io.github.ande1922.moduvera.reference.catalog.CatalogInternalHttpConfiguration;
import io.github.ande1922.moduvera.reference.catalog.CatalogPersistenceConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    CatalogApplicationConfiguration.class,
    CatalogPersistenceConfiguration.class,
    CatalogInternalHttpConfiguration.class
})
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
