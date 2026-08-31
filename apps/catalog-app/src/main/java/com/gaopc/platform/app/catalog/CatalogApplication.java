package com.gaopc.platform.app.catalog;

import com.gaopc.platform.catalog.CatalogApplicationConfiguration;
import com.gaopc.platform.catalog.CatalogInternalHttpConfiguration;
import com.gaopc.platform.catalog.CatalogPersistenceConfiguration;
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
