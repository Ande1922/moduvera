package com.gaopc.platform.catalog;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Compatibility aggregate for applications that need every Catalog capability.
 *
 * @deprecated import the required Catalog configuration slices explicitly.
 */
@Deprecated(forRemoval = true)
@Configuration(proxyBeanMethods = false)
@Import({
    CatalogApplicationConfiguration.class,
    CatalogPersistenceConfiguration.class,
    CatalogInternalHttpConfiguration.class
})
public class CatalogModuleConfiguration {}
