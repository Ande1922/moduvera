package io.github.ande1922.moduvera.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ande1922.moduvera.reference.app.gateway.architecturefixture.GatewayBusinessControllerViolation;
import io.github.ande1922.moduvera.reference.app.inventory.architecturefixture.LeafAppBusinessCallbackViolation;
import io.github.ande1922.moduvera.reference.app.inventory.architecturefixture.LeafAppReliableEndpointViolation;
import io.github.ande1922.moduvera.reference.app.monolith.architecturefixture.MonolithBusinessControllerViolation;
import io.github.ande1922.moduvera.reference.app.order.architecturefixture.LeafAppMessageHandlerViolation;
import io.github.ande1922.moduvera.reference.catalog.api.architecturefixture.TransportApiViolation;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.InventoryAllocationGateway;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.InventoryLookupService;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.InventoryReservationService;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.MessageCoreApiViolation;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.MessagingStarterApiViolation;
import io.github.ande1922.moduvera.reference.inventory.architecturefixture.MisplacedInventoryMessageHandler;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture.InventoryCommandListener;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture.InventoryReservationInboundAdapter;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture.ReservationCommandConsumer;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture.ReservationInventoryLookup;
import io.github.ande1922.moduvera.reference.order.application.architecturefixture.TransportApplicationViolation;
import io.github.ande1922.moduvera.reference.order.architecturefixture.MisplacedOrderController;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class AssemblyArchitectureRulesTest {

    @Test
    void rejectsBusinessControllersInTheGateway() {
        assertViolation(
                ModuveraArchitectureRules.GATEWAY_ONLY_OWNS_EDGE_ROUTES_AND_FILTERS,
                GatewayBusinessControllerViolation.class);
    }

    @Test
    void rejectsBusinessInboundMappingsInAppAssemblies() {
        assertViolation(
                ModuveraArchitectureRules.APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS,
                LeafAppMessageHandlerViolation.class);
        assertViolation(
                ModuveraArchitectureRules.APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS,
                LeafAppBusinessCallbackViolation.class);
        assertViolation(
                ModuveraArchitectureRules.APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS,
                MonolithBusinessControllerViolation.class);
    }

    @Test
    void rejectsReliableTransportMechanicsInAppAssemblies() {
        assertViolation(
                ModuveraArchitectureRules.APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS,
                LeafAppReliableEndpointViolation.class);
    }

    @Test
    void rejectsControllersOutsideProviderHttpInboundPackages() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_HTTP_CONTROLLERS_BELONG_TO_PROVIDER_INBOUND,
                MisplacedOrderController.class);
    }

    @Test
    void rejectsConsumersOutsideProviderMessagingInboundPackages() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_MESSAGE_CONSUMERS_BELONG_TO_PROVIDER_INBOUND,
                MisplacedInventoryMessageHandler.class);
    }

    @Test
    void rejectsRenamedInboundAdaptersForAsyncOnlyInventoryReservation() {
        assertViolation(
                ModuveraArchitectureRules.ASYNC_ONLY_INVENTORY_RESERVATION_DOES_NOT_USE_SYNCHRONOUS_SERVICE_API,
                InventoryReservationInboundAdapter.class);
    }

    @Test
    void rejectsRenamedSynchronousInterfacesForAsyncOnlyInventoryReservation() {
        assertViolation(
                ModuveraArchitectureRules.ASYNC_ONLY_INVENTORY_RESERVATION_DOES_NOT_USE_SYNCHRONOUS_SERVICE_API,
                ReservationCommandConsumer.class,
                InventoryReservationService.class);
    }

    @Test
    void rejectsSynchronousReservationInterfacesWithDifferentReturnTypes() {
        assertViolation(
                ModuveraArchitectureRules.ASYNC_ONLY_INVENTORY_RESERVATION_DOES_NOT_USE_SYNCHRONOUS_SERVICE_API,
                InventoryCommandListener.class,
                InventoryAllocationGateway.class);
    }

    @Test
    void allowsUnrelatedDirectInventoryApis() {
        assertNoViolation(
                ModuveraArchitectureRules.ASYNC_ONLY_INVENTORY_RESERVATION_DOES_NOT_USE_SYNCHRONOUS_SERVICE_API,
                ReservationInventoryLookup.class,
                InventoryLookupService.class);
    }

    @Test
    void rejectsTransportTypesInServiceApis() {
        assertViolation(
                ModuveraArchitectureRules.SERVICE_APIS_ARE_PROTOCOL_NEUTRAL,
                TransportApiViolation.class);
        assertViolation(
                ModuveraArchitectureRules.SERVICE_APIS_ARE_PROTOCOL_NEUTRAL,
                MessagingStarterApiViolation.class);
        assertViolation(
                ModuveraArchitectureRules.SERVICE_APIS_ARE_PROTOCOL_NEUTRAL,
                MessageCoreApiViolation.class);
    }

    @Test
    void rejectsTransportTypesInApplicationCode() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_CORE_IS_TRANSPORT_NEUTRAL,
                TransportApplicationViolation.class);
    }

    private static void assertViolation(ArchRule rule, Class<?>... violatingClasses) {
        JavaClasses classes = new ClassFileImporter().importClasses(violatingClasses);
        assertTrue(rule.evaluate(classes).hasViolation(), () -> "Expected violation for " + classes);
    }

    private static void assertNoViolation(ArchRule rule, Class<?>... conformingClasses) {
        JavaClasses classes = new ClassFileImporter().importClasses(conformingClasses);
        assertFalse(rule.evaluate(classes).hasViolation(), () -> "Expected no violation for " + classes);
    }
}
