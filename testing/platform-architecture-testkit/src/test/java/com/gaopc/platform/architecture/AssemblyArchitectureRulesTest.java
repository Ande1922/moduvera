package com.gaopc.platform.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gaopc.platform.app.gateway.architecturefixture.GatewayBusinessControllerViolation;
import com.gaopc.platform.app.inventory.architecturefixture.LeafAppBusinessCallbackViolation;
import com.gaopc.platform.app.order.architecturefixture.LeafAppMessageHandlerViolation;
import com.gaopc.platform.catalog.api.architecturefixture.TransportApiViolation;
import com.gaopc.platform.inventory.architecturefixture.MisplacedInventoryMessageHandler;
import com.gaopc.platform.order.application.architecturefixture.TransportApplicationViolation;
import com.gaopc.platform.order.architecturefixture.MisplacedOrderController;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class AssemblyArchitectureRulesTest {

    @Test
    void rejectsBusinessControllersInTheGateway() {
        assertViolation(
                PlatformArchitectureRules.GATEWAY_ONLY_OWNS_EDGE_ROUTES_AND_FILTERS,
                GatewayBusinessControllerViolation.class);
    }

    @Test
    void rejectsMessageHandlersInLeafApps() {
        assertViolation(
                PlatformArchitectureRules.LEAF_APPS_ONLY_ASSEMBLE_INBOUND_ADAPTERS,
                LeafAppMessageHandlerViolation.class);
        assertViolation(
                PlatformArchitectureRules.LEAF_APPS_ONLY_ASSEMBLE_INBOUND_ADAPTERS,
                LeafAppBusinessCallbackViolation.class);
    }

    @Test
    void rejectsControllersOutsideProviderHttpInboundPackages() {
        assertViolation(
                PlatformArchitectureRules.BUSINESS_HTTP_CONTROLLERS_BELONG_TO_PROVIDER_INBOUND,
                MisplacedOrderController.class);
    }

    @Test
    void rejectsConsumersOutsideProviderMessagingInboundPackages() {
        assertViolation(
                PlatformArchitectureRules.BUSINESS_MESSAGE_CONSUMERS_BELONG_TO_PROVIDER_INBOUND,
                MisplacedInventoryMessageHandler.class);
    }

    @Test
    void rejectsTransportTypesInServiceApis() {
        assertViolation(
                PlatformArchitectureRules.SERVICE_APIS_ARE_PROTOCOL_NEUTRAL,
                TransportApiViolation.class);
    }

    @Test
    void rejectsTransportTypesInApplicationCode() {
        assertViolation(
                PlatformArchitectureRules.BUSINESS_CORE_IS_TRANSPORT_NEUTRAL,
                TransportApplicationViolation.class);
    }

    private static void assertViolation(ArchRule rule, Class<?> violatingClass) {
        JavaClasses classes = new ClassFileImporter().importClasses(violatingClass);
        assertTrue(rule.evaluate(classes).hasViolation(), () -> "Expected violation for " + violatingClass.getName());
    }
}
