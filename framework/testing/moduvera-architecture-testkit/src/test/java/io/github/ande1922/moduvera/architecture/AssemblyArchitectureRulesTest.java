package io.github.ande1922.moduvera.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ande1922.moduvera.architecturefixture.shipping.api.ShipOrderCommand;
import io.github.ande1922.moduvera.architecturefixture.shipping.api.ShippingBatchService;
import io.github.ande1922.moduvera.architecturefixture.shipping.api.ShippingLookupService;
import io.github.ande1922.moduvera.architecturefixture.shipping.api.ShippingReservationService;
import io.github.ande1922.moduvera.reference.app.gateway.architecturefixture.GatewayBusinessControllerViolation;
import io.github.ande1922.moduvera.reference.app.catalog.architecturefixture.LeafAppMigrationExecutorViolation;
import io.github.ande1922.moduvera.reference.app.inventory.architecturefixture.LeafAppBusinessCallbackViolation;
import io.github.ande1922.moduvera.reference.app.inventory.architecturefixture.LeafAppReliableConsumerFactoryViolation;
import io.github.ande1922.moduvera.reference.app.monolith.architecturefixture.MonolithBusinessControllerViolation;
import io.github.ande1922.moduvera.reference.app.order.architecturefixture.LeafAppBusinessMapper;
import io.github.ande1922.moduvera.reference.app.order.architecturefixture.LeafAppMessageHandlerViolation;
import io.github.ande1922.moduvera.reference.catalog.api.architecturefixture.TransportApiViolation;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.MessageCoreApiViolation;
import io.github.ande1922.moduvera.reference.inventory.api.architecturefixture.MessagingStarterApiViolation;
import io.github.ande1922.moduvera.reference.inventory.architecturefixture.MisplacedInventoryHandler;
import io.github.ande1922.moduvera.reference.inventory.architecturefixture.MisplacedInventoryMessageHandler;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging.architecturefixture.ReliabilityOwningInboundConfiguration;
import io.github.ande1922.moduvera.reference.inventory.adapter.inbound.http.architecturefixture.InboundDependsOnOutboundViolation;
import io.github.ande1922.moduvera.reference.inventory.application.architecturefixture.DescriptorLeakingInventoryHandler;
import io.github.ande1922.moduvera.reference.inventory.application.architecturefixture.RenamedInventoryProcessor;
import io.github.ande1922.moduvera.reference.inventory.application.architecturefixture.TransactionOwningInventoryHandler;
import io.github.ande1922.moduvera.reference.inventory.application.architecturefixture.TransportLeakingInventoryHandler;
import io.github.ande1922.moduvera.reference.inventory.architecturefixture.AdapterActivatingModuleConfiguration;
import io.github.ande1922.moduvera.reference.inventory.configuration.architecturefixture.GenericInventoryConfigurationViolation;
import io.github.ande1922.moduvera.reference.inventory.domain.architecturefixture.DomainDependsOnAdapterViolation;
import io.github.ande1922.moduvera.reference.inventory.misc.architecturefixture.OrphanBusinessServiceClass;
import io.github.ande1922.moduvera.reference.order.adapter.outbound.http.architecturefixture.OutboundDependsOnInboundViolation;
import io.github.ande1922.moduvera.reference.order.application.architecturefixture.ApplicationDependsOnAdapterViolation;
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
        assertViolation(
                ModuveraArchitectureRules.APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS,
                LeafAppBusinessMapper.class);
    }

    @Test
    void rejectsMigrationExecutionInAppAssemblies() {
        assertViolation(
                ModuveraArchitectureRules.APP_ASSEMBLIES_DO_NOT_EXECUTE_MIGRATIONS,
                LeafAppMigrationExecutorViolation.class);
    }

    @Test
    void rejectsReliableTransportMechanicsInAppAssemblies() {
        assertViolation(
                ModuveraArchitectureRules.APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS,
                LeafAppReliableConsumerFactoryViolation.class);
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
                ModuveraArchitectureRules.BUSINESS_MESSAGE_INBOUND_BELONGS_TO_PROVIDER_ADAPTER,
                MisplacedInventoryMessageHandler.class);
        assertViolation(
                ModuveraArchitectureRules.APPLICATION_MESSAGE_HANDLERS_BELONG_TO_APPLICATION,
                MisplacedInventoryHandler.class);
    }

    @Test
    void rejectsGenericBusinessServicePackages() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_SERVICES_AVOID_GENERIC_TOP_LEVEL_PACKAGES,
                GenericInventoryConfigurationViolation.class);
    }

    @Test
    void rejectsBusinessClassesWithoutAnExplicitOwner() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_SERVICE_CLASSES_HAVE_EXPLICIT_OWNERS,
                OrphanBusinessServiceClass.class);
    }

    @Test
    void rejectsBusinessCoreDependenciesOnAdapters() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_CORE_DOES_NOT_DEPEND_ON_ADAPTERS,
                ApplicationDependsOnAdapterViolation.class);
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_CORE_DOES_NOT_DEPEND_ON_ADAPTERS,
                DomainDependsOnAdapterViolation.class);
    }

    @Test
    void rejectsInboundAdaptersDependingOnOutboundAdapters() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_INBOUND_ADAPTERS_DO_NOT_DEPEND_ON_OUTBOUND,
                InboundDependsOnOutboundViolation.class);
    }

    @Test
    void rejectsOutboundAdaptersDependingOnInboundAdapters() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_OUTBOUND_ADAPTERS_DO_NOT_DEPEND_ON_INBOUND,
                OutboundDependsOnInboundViolation.class);
    }

    @Test
    void rejectsModuleConfigurationsThatActivateAdapters() {
        assertViolation(
                ModuveraArchitectureRules.MODULE_CONFIGURATIONS_DO_NOT_ACTIVATE_ADAPTERS,
                AdapterActivatingModuleConfiguration.class);
    }

    @Test
    void rejectsRenamedApplicationMessageHandlers() {
        assertViolation(
                ModuveraArchitectureRules.APPLICATION_MESSAGE_HANDLERS_HAVE_EXPLICIT_NAMES,
                RenamedInventoryProcessor.class);
    }

    @Test
    void rejectsTransportTypesInApplicationMessageHandlers() {
        assertViolation(
                ModuveraArchitectureRules.APPLICATION_MESSAGE_HANDLERS_ARE_PROTOCOL_NEUTRAL,
                TransportLeakingInventoryHandler.class);
        assertViolation(
                ModuveraArchitectureRules.APPLICATION_MESSAGE_HANDLERS_ARE_PROTOCOL_NEUTRAL,
                DescriptorLeakingInventoryHandler.class);
    }

    @Test
    void rejectsApplicationMessageHandlersWithoutInboxOwnership() {
        assertViolation(
                ModuveraArchitectureRules.APPLICATION_MESSAGE_HANDLERS_OWN_INBOX,
                TransportLeakingInventoryHandler.class);
    }

    @Test
    void rejectsApplicationMessageHandlersThatOwnTopLevelTransactions() {
        assertViolation(
                ModuveraArchitectureRules.APPLICATION_MESSAGE_HANDLERS_DO_NOT_OWN_TRANSACTION_BOUNDARY,
                TransactionOwningInventoryHandler.class);
    }

    @Test
    void rejectsMessagingInboundAdaptersThatOwnInboxOrTransactions() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_MESSAGE_INBOUND_DOES_NOT_OWN_INBOX_TRANSACTION,
                ReliabilityOwningInboundConfiguration.class);
    }

    @Test
    void rejectsSynchronousServiceApisForAnyRegisteredAsyncOnlyCapability() {
        ArchRule rule = ModuveraArchitectureRules
                .asyncOnlyCapabilityDoesNotExposeSynchronousServiceApi(ShipOrderCommand.class);
        assertViolation(
                rule,
                ShipOrderCommand.class,
                ShippingReservationService.class);
        assertViolation(
                rule,
                ShipOrderCommand.class,
                ShippingBatchService.class);
    }

    @Test
    void allowsUnrelatedDirectServiceApis() {
        assertNoViolation(
                ModuveraArchitectureRules.asyncOnlyCapabilityDoesNotExposeSynchronousServiceApi(
                        ShipOrderCommand.class),
                ShipOrderCommand.class,
                ShippingLookupService.class);
    }

    @Test
    void rejectsAsyncOnlyCommandsOutsideAProviderApiPackage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuveraArchitectureRules
                        .asyncOnlyCapabilityDoesNotExposeSynchronousServiceApi(
                                AssemblyArchitectureRulesTest.class));
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
