package io.github.ande1922.moduvera.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CurrentScaffoldArchitectureTest {

    private static final String INVENTORY_APPLICATION =
            "io.github.ande1922.moduvera.reference.app.inventory.InventoryApplication";
    private static final String INVENTORY_RESERVATION_HANDLER =
            "io.github.ande1922.moduvera.reference.inventory.application.InventoryReservationHandler";
    private static final String INVENTORY_RESULT_HANDLER =
            "io.github.ande1922.moduvera.reference.order.application.InventoryResultHandler";
    private static final String RELIABLE_INBOUND_ENDPOINT =
            "io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint";
    private static final String RELIABLE_MESSAGE_CONSUMER_FACTORY =
            "io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory";
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
        assertImported(
                "io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http.CatalogHttpController");
        assertImported(INVENTORY_RESERVATION_HANDLER);
        assertImported("io.github.ande1922.moduvera.reference.order.adapter.inbound.http.OrderHttpController");
        assertImported(INVENTORY_RESULT_HANDLER);
        assertImported(RELIABLE_INBOUND_ENDPOINT);
        assertImported(RELIABLE_MESSAGE_CONSUMER_FACTORY);
        assertFalse(CURRENT_CLASSES.contain(
                "io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumer"));
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
        ModuveraArchitectureRules.BUSINESS_SERVICES_AVOID_GENERIC_TOP_LEVEL_PACKAGES
                .check(CURRENT_CLASSES);
    }

    @Test
    void businessServiceClassesBelongToExplicitOwnedSurfaces() {
        Set<String> businessServiceClasses = CURRENT_CLASSES.stream()
                .map(javaClass -> javaClass.getName())
                .filter(className -> className.startsWith("io.github.ande1922.moduvera.reference.catalog.")
                        || className.startsWith("io.github.ande1922.moduvera.reference.inventory.")
                        || className.startsWith("io.github.ande1922.moduvera.reference.order."))
                .collect(Collectors.toSet());

        assertTrue(businessServiceClasses.contains(
                "io.github.ande1922.moduvera.reference.catalog.catalog.CatalogModuleConfiguration"));
        assertTrue(businessServiceClasses.contains(
                "io.github.ande1922.moduvera.reference.inventory.InventoryModuleConfiguration"));
        assertTrue(businessServiceClasses.contains(
                "io.github.ande1922.moduvera.reference.order.OrderModuleConfiguration"));
        assertTrue(businessServiceClasses.contains(INVENTORY_RESERVATION_HANDLER));
        assertTrue(businessServiceClasses.contains(INVENTORY_RESULT_HANDLER));
        ModuveraArchitectureRules.BUSINESS_SERVICE_CLASSES_HAVE_EXPLICIT_OWNERS.check(CURRENT_CLASSES);
    }

    @Test
    void businessModulesAndAdapterDirectionsStayExplicit() {
        ModuveraArchitectureRules.BUSINESS_CORE_DOES_NOT_DEPEND_ON_ADAPTERS.check(CURRENT_CLASSES);
        ModuveraArchitectureRules.BUSINESS_ADAPTER_DIRECTIONS_DO_NOT_CROSS.check(CURRENT_CLASSES);
        ModuveraArchitectureRules.MODULE_CONFIGURATIONS_DO_NOT_ACTIVATE_ADAPTERS
                .check(CURRENT_CLASSES);
    }

    @Test
    void gatewayOnlyOwnsEdgeRoutesAndFilters() {
        ModuveraArchitectureRules.GATEWAY_ONLY_OWNS_EDGE_ROUTES_AND_FILTERS.check(CURRENT_CLASSES);
    }

    @Test
    void appAssembliesOnlySelectAndActivateInboundAdapters() {
        ModuveraArchitectureRules.APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS.check(CURRENT_CLASSES);
        ModuveraArchitectureRules.APP_ASSEMBLIES_DO_NOT_EXECUTE_MIGRATIONS.check(CURRENT_CLASSES);
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
        ModuveraArchitectureRules.BUSINESS_MESSAGE_INBOUND_BELONGS_TO_PROVIDER_ADAPTER
                .check(CURRENT_CLASSES);
        ModuveraArchitectureRules.APPLICATION_MESSAGE_HANDLERS_HAVE_APPLICATION_OWNERSHIP
                .check(CURRENT_CLASSES);
        ModuveraArchitectureRules.BUSINESS_MESSAGE_INBOUND_DOES_NOT_OWN_INBOX_TRANSACTION
                .check(CURRENT_CLASSES);
        ModuveraArchitectureRules.RELIABLE_CONSUMER_TRANSPORT_DOES_NOT_OWN_BUSINESS_TRANSACTION
                .check(CURRENT_CLASSES);
    }

    @Test
    void reliableInboundEndpointOwnsOnlyTheConsumerTransportBoundary() {
        var endpoint = CURRENT_CLASSES.get(RELIABLE_INBOUND_ENDPOINT);
        Set<String> dependencies = endpoint.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName())
                .collect(Collectors.toSet());

        assertTrue(endpoint.isAssignableTo(java.util.function.Consumer.class));
        assertTrue(dependencies.contains("io.github.ande1922.moduvera.context.ExecutionContextHolder"));
        assertTrue(dependencies.contains("io.github.ande1922.moduvera.message.SerializedMessage"));
        assertTrue(dependencies.contains("io.github.ande1922.moduvera.message.NonRetryableMessageException"));
        assertTrue(dependencies.contains("java.lang.Thread"));
        assertTrue(dependencies.contains("java.time.Duration"));
        assertFalse(dependencies.contains("io.github.ande1922.moduvera.message.inbox.InboxTemplate"));
        assertFalse(dependencies.contains("io.github.ande1922.moduvera.data.TransactionBoundary"));
        assertFalse(dependencies.contains(
                "io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler"));
    }

    @Test
    void asyncOnlyInventoryReservationUsesTheApplicationHandlerPath() {
        Set<String> selectedHandlers = CURRENT_CLASSES.stream()
                .filter(ModuveraArchitectureRules.INVENTORY_RESERVATION_APPLICATION_HANDLER)
                .map(javaClass -> javaClass.getName())
                .collect(Collectors.toSet());

        assertTrue(
                selectedHandlers.contains(INVENTORY_RESERVATION_HANDLER),
                () -> "Inventory reservation rule did not select the current handler: "
                        + selectedHandlers);
        ModuveraArchitectureRules.asyncOnlyCapabilityDoesNotExposeSynchronousServiceApi(
                        ReserveInventoryCommand.class)
                .check(CURRENT_CLASSES);
    }

    private static void assertImported(String className) {
        assertTrue(CURRENT_CLASSES.contain(className), () -> "Architecture classpath is missing " + className);
    }
}
