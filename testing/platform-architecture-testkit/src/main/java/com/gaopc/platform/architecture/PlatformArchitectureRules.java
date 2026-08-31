package com.gaopc.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ArchRule;

public final class PlatformArchitectureRules {

    private static final String[] BUSINESS_SERVICE_PACKAGES = {
        "com.gaopc.platform.catalog..",
        "com.gaopc.platform.inventory..",
        "com.gaopc.platform.order.."
    };
    private static final String[] BUSINESS_HTTP_INBOUND_PACKAGES = {
        "com.gaopc.platform.catalog.inbound.http..",
        "com.gaopc.platform.inventory.inbound.http..",
        "com.gaopc.platform.order.inbound.http.."
    };
    private static final String[] BUSINESS_MESSAGING_INBOUND_PACKAGES = {
        "com.gaopc.platform.catalog.inbound.messaging..",
        "com.gaopc.platform.inventory.inbound.messaging..",
        "com.gaopc.platform.order.inbound.messaging.."
    };
    private static final String[] APP_ASSEMBLY_PACKAGES = {
        "com.gaopc.platform.app.catalog..",
        "com.gaopc.platform.app.inventory..",
        "com.gaopc.platform.app.order..",
        "com.gaopc.platform.app.monolith.."
    };
    private static final String GATEWAY_PACKAGE = "com.gaopc.platform.app.gateway..";
    private static final String[] BUSINESS_APPLICATION_PACKAGES = {
        "com.gaopc.platform.catalog.application..",
        "com.gaopc.platform.inventory.application..",
        "com.gaopc.platform.order.application.."
    };
    private static final String REQUEST_MAPPING =
            "org.springframework.web.bind.annotation.RequestMapping";
    private static final String REST_CONTROLLER =
            "org.springframework.web.bind.annotation.RestController";

    public static final ArchRule SERVICE_APIS_ARE_PROTOCOL_NEUTRAL = noClasses()
            .that()
            .resideInAPackage("com.gaopc.platform..api..")
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
                    "com.gaopc.platform..inbound..",
                    "com.gaopc.platform..infrastructure..");

    public static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_FRAMEWORKS_OR_ADAPTERS = noClasses()
            .that()
            .resideInAPackage("com.gaopc.platform..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.jooq..",
                    "com.baomidou.mybatisplus..",
                    "org.apache.kafka..",
                    "org.apache.rocketmq..",
                    "com.gaopc.platform..infrastructure..");

    public static final ArchRule BUSINESS_CORE_IS_TRANSPORT_NEUTRAL = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.gaopc.platform..application..", "com.gaopc.platform..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..",
                    "org.springframework.messaging..",
                    "org.springframework.cloud.stream..",
                    "org.apache.kafka..",
                    "org.apache.rocketmq..",
                    "tools.jackson..",
                    "com.gaopc.platform.messaging.kafka..",
                    "com.gaopc.platform.message.InboundMessageContract");

    public static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage("com.gaopc.platform..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.gaopc.platform..infrastructure..");

    public static final ArchRule BUSINESS_LAYERS_DO_NOT_READ_EXECUTION_CONTEXT = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.gaopc.platform..application..", "com.gaopc.platform..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.gaopc.platform.context..");

    public static final ArchRule BUSINESS_LAYERS_DO_NOT_MANAGE_THREADS = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.gaopc.platform..application..", "com.gaopc.platform..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.util.concurrent..");

    public static final ArchRule BUSINESS_LAYERS_DO_NOT_USE_THREAD_CONTEXT_PRIMITIVES = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.gaopc.platform..application..", "com.gaopc.platform..domain..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching("java\\.lang\\.(ThreadLocal|ScopedValue)");

    public static final ArchRule PRODUCTION_DOES_NOT_DEPEND_ON_TEST_SUPPORT = noClasses()
            .that()
            .resideOutsideOfPackage("com.gaopc.platform.testing..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.gaopc.platform.testing..");

    public static final ArchRule OUTBOX_RELAY_INTERNALS_DO_NOT_LEAK_INTO_BUSINESS_MODULES = noClasses()
            .that()
            .resideOutsideOfPackages(
                    "com.gaopc.platform.message.outbox..",
                    "com.gaopc.platform.messaging.kafka..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.gaopc.platform.message.outbox..");

    public static final ArchRule NO_GENERIC_DUMPING_GROUND_PACKAGES = noClasses()
            .should()
            .resideInAnyPackage(
                    "com.gaopc.platform..common..",
                    "com.gaopc.platform..utils..",
                    "com.gaopc.platform..util..",
                    "com.gaopc.platform..port..",
                    "com.gaopc.platform..ports..");

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
                                    "com.gaopc.platform.message..",
                                    "com.gaopc.platform.messaging.kafka..",
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
                                    "com\\.gaopc\\.platform\\.web\\.ProblemStatus(Contributor|Resolver)"))
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
                                    "com.gaopc.platform.message.InboundMessageContract",
                                    "com.gaopc.platform.messaging.kafka.ReliableMessageConsumer",
                                    "com.gaopc.platform.messaging.kafka.ReliableMessageConsumerFactory"))
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

    private PlatformArchitectureRules() {}
}
