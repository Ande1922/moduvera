package io.github.ande1922.moduvera.reference.catalog;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Compatibility facade for applications that still select the Catalog business module by its
 * service-level identity.
 *
 * @deprecated import the required Catalog configuration slices explicitly.
 */
@Deprecated(forRemoval = true)
@Configuration(proxyBeanMethods = false)
@Import(io.github.ande1922.moduvera.reference.catalog.catalog.CatalogModuleConfiguration.class)
public class CatalogModuleConfiguration {}
