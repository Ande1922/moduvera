package io.github.ande1922.moduvera.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ande1922.moduvera.reference.app.gateway.architecturefixture.GatewayBusinessControllerViolation;
import io.github.ande1922.moduvera.reference.app.inventory.architecturefixture.LeafAppBusinessCallbackViolation;
import io.github.ande1922.moduvera.reference.app.monolith.architecturefixture.MonolithBusinessControllerViolation;
import io.github.ande1922.moduvera.reference.app.order.architecturefixture.LeafAppMessageHandlerViolation;
import io.github.ande1922.moduvera.reference.catalog.api.architecturefixture.TransportApiViolation;
import io.github.ande1922.moduvera.reference.inventory.architecturefixture.MisplacedInventoryMessageHandler;
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
    void rejectsTransportTypesInServiceApis() {
        assertViolation(
                ModuveraArchitectureRules.SERVICE_APIS_ARE_PROTOCOL_NEUTRAL,
                TransportApiViolation.class);
    }

    @Test
    void rejectsTransportTypesInApplicationCode() {
        assertViolation(
                ModuveraArchitectureRules.BUSINESS_CORE_IS_TRANSPORT_NEUTRAL,
                TransportApplicationViolation.class);
    }

    private static void assertViolation(ArchRule rule, Class<?> violatingClass) {
        JavaClasses classes = new ClassFileImporter().importClasses(violatingClass);
        assertTrue(rule.evaluate(classes).hasViolation(), () -> "Expected violation for " + violatingClass.getName());
    }
}
