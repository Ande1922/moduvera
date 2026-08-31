package com.gaopc.platform.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class CurrentScaffoldArchitectureTest {

    private static final JavaClasses CURRENT_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.gaopc.platform");

    @Test
    void architectureClasspathContainsAssembliesAndInboundOwners() {
        assertImported("com.gaopc.platform.app.catalog.CatalogApplication");
        assertImported("com.gaopc.platform.app.inventory.InventoryApplication");
        assertImported("com.gaopc.platform.app.order.OrderApplication");
        assertImported("com.gaopc.platform.app.gateway.GatewayApplication");
        assertImported("com.gaopc.platform.catalog.inbound.http.CatalogHttpController");
        assertImported("com.gaopc.platform.inventory.inbound.messaging.ReserveInventoryCommandInboundConfiguration");
        assertImported("com.gaopc.platform.order.inbound.http.OrderHttpController");
        assertImported("com.gaopc.platform.order.inbound.messaging.InventoryResultInboundConfiguration");
    }

    @Test
    void serviceApisStayProtocolNeutral() {
        PlatformArchitectureRules.SERVICE_APIS_ARE_PROTOCOL_NEUTRAL.check(CURRENT_CLASSES);
    }

    @Test
    void domainStaysIndependentFromFrameworksAndAdapters() {
        PlatformArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_FRAMEWORKS_OR_ADAPTERS.check(CURRENT_CLASSES);
    }

    @Test
    void domainAndApplicationStayIndependentFromTransportFrameworks() {
        PlatformArchitectureRules.BUSINESS_CORE_IS_TRANSPORT_NEUTRAL.check(CURRENT_CLASSES);
    }

    @Test
    void applicationDoesNotReachIntoInfrastructure() {
        PlatformArchitectureRules.APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE.check(CURRENT_CLASSES);
    }

    @Test
    void businessLayersDoNotReadExecutionContextDirectly() {
        PlatformArchitectureRules.BUSINESS_LAYERS_DO_NOT_READ_EXECUTION_CONTEXT.check(CURRENT_CLASSES);
        PlatformArchitectureRules.BUSINESS_LAYERS_DO_NOT_MANAGE_THREADS.check(CURRENT_CLASSES);
        PlatformArchitectureRules.BUSINESS_LAYERS_DO_NOT_USE_THREAD_CONTEXT_PRIMITIVES.check(CURRENT_CLASSES);
    }

    @Test
    void productionDoesNotDependOnTestSupport() {
        PlatformArchitectureRules.PRODUCTION_DOES_NOT_DEPEND_ON_TEST_SUPPORT.check(CURRENT_CLASSES);
    }

    @Test
    void businessModulesCannotBypassPublicationGuaranteesThroughOutboxInternals() {
        PlatformArchitectureRules.OUTBOX_RELAY_INTERNALS_DO_NOT_LEAK_INTO_BUSINESS_MODULES
                .check(CURRENT_CLASSES);
    }

    @Test
    void genericDumpingGroundPackagesStayAbsent() {
        PlatformArchitectureRules.NO_GENERIC_DUMPING_GROUND_PACKAGES.check(CURRENT_CLASSES);
    }

    @Test
    void gatewayOnlyOwnsEdgeRoutesAndFilters() {
        PlatformArchitectureRules.GATEWAY_ONLY_OWNS_EDGE_ROUTES_AND_FILTERS.check(CURRENT_CLASSES);
    }

    @Test
    void leafAppsOnlyAssembleInboundAdapters() {
        PlatformArchitectureRules.LEAF_APPS_ONLY_ASSEMBLE_INBOUND_ADAPTERS.check(CURRENT_CLASSES);
    }

    @Test
    void businessInboundAdaptersBelongToTheirProvidingService() {
        PlatformArchitectureRules.BUSINESS_HTTP_CONTROLLERS_BELONG_TO_PROVIDER_INBOUND.check(CURRENT_CLASSES);
        PlatformArchitectureRules.BUSINESS_MESSAGE_CONSUMERS_BELONG_TO_PROVIDER_INBOUND.check(CURRENT_CLASSES);
    }

    private static void assertImported(String className) {
        assertTrue(CURRENT_CLASSES.contain(className), () -> "Architecture classpath is missing " + className);
    }
}
