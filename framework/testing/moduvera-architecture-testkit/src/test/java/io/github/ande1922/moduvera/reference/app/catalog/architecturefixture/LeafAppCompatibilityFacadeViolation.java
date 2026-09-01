package io.github.ande1922.moduvera.reference.app.catalog.architecturefixture;

import io.github.ande1922.moduvera.reference.catalog.CatalogModuleConfiguration;

@SuppressWarnings("removal")
public final class LeafAppCompatibilityFacadeViolation {

    private final CatalogModuleConfiguration facade;

    public LeafAppCompatibilityFacadeViolation(CatalogModuleConfiguration facade) {
        this.facade = facade;
    }

    public CatalogModuleConfiguration facade() {
        return facade;
    }
}
