package io.github.ande1922.moduvera.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CurrentScaffoldArchitectureTest {

    private static final String INVENTORY_APPLICATION =
            "io.github.ande1922.moduvera.reference.app.inventory.InventoryApplication";
    private static final String RESERVE_INVENTORY_COMMAND_MESSAGE_HANDLER =
            "io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.ReserveInventoryCommandMessageHandler";
    private static final JavaClasses CURRENT_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ande1922.moduvera");

    @Test
    void architectureClasspathContainsAssembliesAndInboundOwners() {
        assertImported("io.github.ande1922.moduvera.reference.app.catalog.CatalogApplication");
        assertImported(INVENTORY_APPLICATION);
        assertImported("io.github.ande1922.moduvera.reference.app.order.OrderApplication");
        assertImported("io.github.ande1922.moduvera.reference.app.gateway.GatewayApplication");
        assertImported("io.github.ande1922.moduvera.reference.app.monolith.ModuveraMonolithApplication");
        assertImported("io.github.ande1922.moduvera.reference.catalog.inbound.http.CatalogHttpController");
        assertImported(RESERVE_INVENTORY_COMMAND_MESSAGE_HANDLER);
        assertImported("io.github.ande1922.moduvera.reference.order.inbound.http.OrderHttpController");
        assertImported("io.github.ande1922.moduvera.reference.order.inbound.messaging.InventoryResultInboundConfiguration");
    }

    @Test
    void serviceApisStayProtocolNeutral() {
        ModuveraArchitectureRules.SERVICE_APIS_ARE_PROTOCOL_NEUTRAL.check(CURRENT_CLASSES);
    }

    @Test
    void domainStaysIndependentFromFrameworksAndAdapters() {
        ModuveraArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_FRAMEWORKS_OR_ADAPTERS.check(CURRENT_CLASSES);
    }

    @Test
    void domainAndApplicationStayIndependentFromTransportFrameworks() {
        ModuveraArchitectureRules.BUSINESS_CORE_IS_TRANSPORT_NEUTRAL.check(CURRENT_CLASSES);
    }

    @Test
    void applicationDoesNotReachIntoInfrastructure() {
        ModuveraArchitectureRules.APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE.check(CURRENT_CLASSES);
    }

    @Test
    void businessLayersDoNotReadExecutionContextDirectly() {
        ModuveraArchitectureRules.BUSINESS_LAYERS_DO_NOT_READ_EXECUTION_CONTEXT.check(CURRENT_CLASSES);
        ModuveraArchitectureRules.BUSINESS_LAYERS_DO_NOT_MANAGE_THREADS.check(CURRENT_CLASSES);
        ModuveraArchitectureRules.BUSINESS_LAYERS_DO_NOT_USE_THREAD_CONTEXT_PRIMITIVES.check(CURRENT_CLASSES);
    }

    @Test
    void productionDoesNotDependOnTestSupport() {
        ModuveraArchitectureRules.PRODUCTION_DOES_NOT_DEPEND_ON_TEST_SUPPORT.check(CURRENT_CLASSES);
    }

    @Test
    void businessModulesCannotBypassPublicationGuaranteesThroughOutboxInternals() {
        ModuveraArchitectureRules.OUTBOX_RELAY_INTERNALS_DO_NOT_LEAK_INTO_BUSINESS_MODULES
                .check(CURRENT_CLASSES);
    }

    @Test
    void genericDumpingGroundPackagesStayAbsent() {
        ModuveraArchitectureRules.NO_GENERIC_DUMPING_GROUND_PACKAGES.check(CURRENT_CLASSES);
    }

    @Test
    void gatewayOnlyOwnsEdgeRoutesAndFilters() {
        ModuveraArchitectureRules.GATEWAY_ONLY_OWNS_EDGE_ROUTES_AND_FILTERS.check(CURRENT_CLASSES);
    }

    @Test
    void appAssembliesOnlySelectAndActivateInboundAdapters() {
        ModuveraArchitectureRules.APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS.check(CURRENT_CLASSES);
    }

    @Test
    void inventoryAppSelectsOnlyThePublicMessagingPlatformDefinition() {
        Set<String> messagingKafkaDependencies = CURRENT_CLASSES.get(INVENTORY_APPLICATION)
                .getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName())
                .filter(className -> className.startsWith(
                        "io.github.ande1922.moduvera.messaging.kafka."))
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(ModuveraArchitectureRules.MESSAGING_MIGRATION_CONFIGURATION),
                messagingKafkaDependencies);
    }

    @Test
    void businessInboundAdaptersBelongToTheirProvidingService() {
        ModuveraArchitectureRules.BUSINESS_HTTP_CONTROLLERS_BELONG_TO_PROVIDER_INBOUND.check(CURRENT_CLASSES);
        ModuveraArchitectureRules.BUSINESS_MESSAGE_CONSUMERS_BELONG_TO_PROVIDER_INBOUND.check(CURRENT_CLASSES);
    }

    @Test
    void asyncOnlyInventoryReservationUsesTheDirectApplicationServicePath() {
        Set<String> selectedAdapters = CURRENT_CLASSES.stream()
                .filter(ModuveraArchitectureRules.INVENTORY_RESERVATION_MESSAGE_ADAPTER)
                .map(javaClass -> javaClass.getName())
                .collect(Collectors.toSet());

        assertTrue(
                selectedAdapters.contains(RESERVE_INVENTORY_COMMAND_MESSAGE_HANDLER),
                () -> "Inventory reservation rule did not select the current handler: "
                        + selectedAdapters);
        ModuveraArchitectureRules.ASYNC_ONLY_INVENTORY_RESERVATION_DOES_NOT_USE_SYNCHRONOUS_SERVICE_API
                .check(CURRENT_CLASSES);
    }

    private static void assertImported(String className) {
        assertTrue(CURRENT_CLASSES.contain(className), () -> "Architecture classpath is missing " + className);
    }
}
