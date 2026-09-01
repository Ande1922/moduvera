package io.github.ande1922.moduvera.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasModifiers;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ArchRule;

public final class ModuveraArchitectureRules {

    private static final String[] BUSINESS_SERVICE_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog..",
        "io.github.ande1922.moduvera.reference.inventory..",
        "io.github.ande1922.moduvera.reference.order.."
    };
    private static final String[] BUSINESS_HTTP_INBOUND_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog.inbound.http..",
        "io.github.ande1922.moduvera.reference.inventory.inbound.http..",
        "io.github.ande1922.moduvera.reference.order.inbound.http.."
    };
    private static final String[] BUSINESS_MESSAGING_INBOUND_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog.inbound.messaging..",
        "io.github.ande1922.moduvera.reference.inventory.inbound.messaging..",
        "io.github.ande1922.moduvera.reference.order.inbound.messaging.."
    };
    private static final String[] APP_ASSEMBLY_PACKAGES = {
        "io.github.ande1922.moduvera.reference.app.catalog..",
        "io.github.ande1922.moduvera.reference.app.inventory..",
        "io.github.ande1922.moduvera.reference.app.order..",
        "io.github.ande1922.moduvera.reference.app.monolith.."
    };
    private static final String GATEWAY_PACKAGE = "io.github.ande1922.moduvera.reference.app.gateway..";
    private static final String[] BUSINESS_APPLICATION_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog.application..",
        "io.github.ande1922.moduvera.reference.inventory.application..",
        "io.github.ande1922.moduvera.reference.order.application.."
    };
    private static final String REQUEST_MAPPING =
            "org.springframework.web.bind.annotation.RequestMapping";
    private static final String REST_CONTROLLER =
            "org.springframework.web.bind.annotation.RestController";
    private static final String RESERVE_INVENTORY_COMMAND =
            "io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand";
    private static final String INVENTORY_RESERVATION_RESULT =
            "io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult";
    private static final DescribedPredicate<JavaClass> INVENTORY_RESERVATION_MESSAGE_ADAPTER =
            JavaClass.Predicates.resideInAPackage(
                            "io.github.ande1922.moduvera.reference.inventory.inbound.messaging..")
                    .and(DescribedPredicate.describe(
                            "depend on the provider-owned Reserve Inventory Command",
                            javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                                    .anyMatch(dependency -> dependency
                                            .getTargetClass()
                                            .getName()
                                            .equals(RESERVE_INVENTORY_COMMAND))))
                    .as("Inventory messaging inbound classes that consume the Reserve Inventory Command");
    private static final DescribedPredicate<JavaMethod> SYNCHRONOUS_INVENTORY_RESERVATION_OPERATION =
            DescribedPredicate.describe(
                    "accept the Reserve Inventory Command and return the Inventory Reservation Result",
                    method -> method.getRawParameterTypes().stream()
                                    .anyMatch(parameter -> parameter.getName().equals(RESERVE_INVENTORY_COMMAND))
                            && method.getRawReturnType().getName().equals(INVENTORY_RESERVATION_RESULT));
    private static final DescribedPredicate<JavaClass> SYNCHRONOUS_INVENTORY_RESERVATION_INTERFACE =
            JavaClass.Predicates.resideInAPackage(
                            "io.github.ande1922.moduvera.reference.inventory.api..")
                    .and(JavaClass.Predicates.INTERFACES)
                    .and(HasModifiers.Predicates.modifier(JavaModifier.PUBLIC))
                    .and(JavaClass.Predicates.containAnyMethodsThat(
                            SYNCHRONOUS_INVENTORY_RESERVATION_OPERATION))
                    .as("public Inventory API interfaces that expose synchronous inventory reservation");

    public static final ArchRule SERVICE_APIS_ARE_PROTOCOL_NEUTRAL = noClasses()
            .that()
            .resideInAPackage("io.github.ande1922.moduvera..api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.jooq..",
                    "com.baomidou.mybatisplus..",
                    "org.apache.kafka..",
                    "org.apache.rocketmq..",
                    "tools.jackson..",
                    "io.github.ande1922.moduvera.message..",
                    "io.github.ande1922.moduvera.messaging..",
                    "io.github.ande1922.moduvera..inbound..",
                    "io.github.ande1922.moduvera..infrastructure..");

    public static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_FRAMEWORKS_OR_ADAPTERS = noClasses()
            .that()
            .resideInAPackage("io.github.ande1922.moduvera..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.jooq..",
                    "com.baomidou.mybatisplus..",
                    "org.apache.kafka..",
                    "org.apache.rocketmq..",
                    "io.github.ande1922.moduvera..infrastructure..");

    public static final ArchRule BUSINESS_CORE_IS_TRANSPORT_NEUTRAL = noClasses()
            .that()
            .resideInAnyPackage(
                    "io.github.ande1922.moduvera..application..", "io.github.ande1922.moduvera..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..",
                    "org.springframework.messaging..",
                    "org.springframework.cloud.stream..",
                    "org.apache.kafka..",
                    "org.apache.rocketmq..",
                    "tools.jackson..",
                    "io.github.ande1922.moduvera.messaging.kafka..",
                    "io.github.ande1922.moduvera.message.InboundMessageContract");

    public static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage("io.github.ande1922.moduvera..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.github.ande1922.moduvera..infrastructure..");

    public static final ArchRule BUSINESS_LAYERS_DO_NOT_READ_EXECUTION_CONTEXT = noClasses()
            .that()
            .resideInAnyPackage(
                    "io.github.ande1922.moduvera..application..", "io.github.ande1922.moduvera..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.github.ande1922.moduvera.context..");

    public static final ArchRule BUSINESS_LAYERS_DO_NOT_MANAGE_THREADS = noClasses()
            .that()
            .resideInAnyPackage(
                    "io.github.ande1922.moduvera..application..", "io.github.ande1922.moduvera..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.util.concurrent..");

    public static final ArchRule BUSINESS_LAYERS_DO_NOT_USE_THREAD_CONTEXT_PRIMITIVES = noClasses()
            .that()
            .resideInAnyPackage(
                    "io.github.ande1922.moduvera..application..", "io.github.ande1922.moduvera..domain..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching("java\\.lang\\.(ThreadLocal|ScopedValue)");

    public static final ArchRule PRODUCTION_DOES_NOT_DEPEND_ON_TEST_SUPPORT = noClasses()
            .that()
            .resideOutsideOfPackage("io.github.ande1922.moduvera.testing..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.github.ande1922.moduvera.testing..");

    public static final ArchRule OUTBOX_RELAY_INTERNALS_DO_NOT_LEAK_INTO_BUSINESS_MODULES = noClasses()
            .that()
            .resideOutsideOfPackages(
                    "io.github.ande1922.moduvera.message.outbox..",
                    "io.github.ande1922.moduvera.messaging.kafka..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.github.ande1922.moduvera.message.outbox..");

    public static final ArchRule NO_GENERIC_DUMPING_GROUND_PACKAGES = noClasses()
            .should()
            .resideInAnyPackage(
                    "io.github.ande1922.moduvera..common..",
                    "io.github.ande1922.moduvera..utils..",
                    "io.github.ande1922.moduvera..util..",
                    "io.github.ande1922.moduvera..port..",
                    "io.github.ande1922.moduvera..ports..");

    public static final ArchRule GATEWAY_ONLY_OWNS_EDGE_ROUTES_AND_FILTERS =
            withoutHttpMappingsIn(GATEWAY_PACKAGE)
                    .and(noClasses()
                            .that()
                            .resideInAPackage(GATEWAY_PACKAGE)
                            .should()
                            .dependOnClassesThat()
                            .resideInAnyPackage(BUSINESS_SERVICE_PACKAGES))
                    .as("the Gateway must not declare HTTP endpoints or depend on Business Service code");

    public static final ArchRule APP_ASSEMBLIES_ONLY_ASSEMBLE_INBOUND_ADAPTERS =
            withoutHttpMappingsIn(APP_ASSEMBLY_PACKAGES)
                    .and(noClasses()
                            .that()
                            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
                            .should()
                            .dependOnClassesThat()
                            .resideInAnyPackage(
                                    "org.springframework.messaging..",
                                    "io.github.ande1922.moduvera.message..",
                                    "io.github.ande1922.moduvera.messaging.kafka..",
                                    "tools.jackson.."))
                    .and(noClasses()
                            .that()
                            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
                            .should()
                            .dependOnClassesThat()
                            .resideInAnyPackage(BUSINESS_APPLICATION_PACKAGES))
                    .and(noClasses()
                            .that()
                            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
                            .should()
                            .dependOnClassesThat()
                            .haveNameMatching(
                                    "io\\.github\\.ande1922\\.moduvera\\.web\\.ProblemStatus(Contributor|Resolver)"))
                    .and(noMethods()
                            .that()
                            .areDeclaredInClassesThat()
                            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
                            .should()
                            .haveRawReturnType(java.util.function.Consumer.class))
                    .and(noClasses()
                            .that()
                            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
                            .should()
                            .haveSimpleNameEndingWith("MessageMapper"))
                    .and(noClasses()
                            .that()
                            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
                            .should()
                            .haveSimpleNameEndingWith("MessageHandler"))
                    .as("App Assemblies must only select and activate business inbound adapters");

    public static final ArchRule BUSINESS_HTTP_CONTROLLERS_BELONG_TO_PROVIDER_INBOUND = noClasses()
            .that()
            .resideInAnyPackage(BUSINESS_SERVICE_PACKAGES)
            .and()
            .resideOutsideOfPackages(BUSINESS_HTTP_INBOUND_PACKAGES)
            .should()
            .beAnnotatedWith(REST_CONTROLLER)
            .orShould()
            .beMetaAnnotatedWith(REST_CONTROLLER)
            .as("Business Service HTTP Controllers must reside in the provider inbound.http package");

    public static final ArchRule BUSINESS_MESSAGE_CONSUMERS_BELONG_TO_PROVIDER_INBOUND =
            CompositeArchRule.of(noClasses()
                            .that()
                            .resideInAnyPackage(BUSINESS_SERVICE_PACKAGES)
                            .and()
                            .resideOutsideOfPackages(BUSINESS_MESSAGING_INBOUND_PACKAGES)
                            .should()
                            .dependOnClassesThat()
                            .resideInAnyPackage(
                                    "org.springframework.messaging..",
                                    "io.github.ande1922.moduvera.message.InboundMessageContract",
                                    "io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumer",
                                    "io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory"))
                    .and(noClasses()
                            .that()
                            .resideInAnyPackage(BUSINESS_SERVICE_PACKAGES)
                            .and()
                            .resideOutsideOfPackages(BUSINESS_MESSAGING_INBOUND_PACKAGES)
                            .should()
                            .haveSimpleNameEndingWith("MessageHandler"))
                    .and(noClasses()
                            .that()
                            .resideInAnyPackage(BUSINESS_SERVICE_PACKAGES)
                            .and()
                            .resideOutsideOfPackages(BUSINESS_MESSAGING_INBOUND_PACKAGES)
                            .should()
                            .haveSimpleNameEndingWith("MessageMapper"))
                    .and(noMethods()
                            .that()
                            .areDeclaredInClassesThat(JavaClass.Predicates.resideInAnyPackage(BUSINESS_SERVICE_PACKAGES)
                                    .and(JavaClass.Predicates.resideOutsideOfPackages(
                                            BUSINESS_MESSAGING_INBOUND_PACKAGES)))
                            .should()
                            .haveRawReturnType(java.util.function.Consumer.class))
                    .as("Business Service message Consumers and payload mappers must reside in the provider inbound.messaging package");

    public static final ArchRule ASYNC_ONLY_INVENTORY_RESERVATION_DOES_NOT_USE_SYNCHRONOUS_SERVICE_API =
            noClasses()
                    .that(INVENTORY_RESERVATION_MESSAGE_ADAPTER)
                    .should()
                    .dependOnClassesThat(SYNCHRONOUS_INVENTORY_RESERVATION_INTERFACE)
                    .as("the async-only Inventory reservation handler must invoke the Application Service directly, not a synchronous public Service API");

    private static CompositeArchRule withoutHttpMappingsIn(String... packages) {
        return CompositeArchRule.of(noClasses()
                        .that()
                        .resideInAnyPackage(packages)
                        .should()
                        .beAnnotatedWith(REST_CONTROLLER))
                .and(noMethods()
                        .that()
                        .areDeclaredInClassesThat()
                        .resideInAnyPackage(packages)
                        .should()
                        .beAnnotatedWith(REQUEST_MAPPING))
                .and(noMethods()
                        .that()
                        .areDeclaredInClassesThat()
                        .resideInAnyPackage(packages)
                        .should()
                        .beMetaAnnotatedWith(REQUEST_MAPPING));
    }

    private ModuveraArchitectureRules() {}
}
