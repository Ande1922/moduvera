package io.github.ande1922.moduvera.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

public final class ModuveraArchitectureRules {

    private static final String[] BUSINESS_SERVICE_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog..",
        "io.github.ande1922.moduvera.reference.inventory..",
        "io.github.ande1922.moduvera.reference.order.."
    };
    private static final String[] BUSINESS_HTTP_INBOUND_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.http..",
        "io.github.ande1922.moduvera.reference.inventory.adapter.inbound.http..",
        "io.github.ande1922.moduvera.reference.order.adapter.inbound.http.."
    };
    private static final String[] BUSINESS_MESSAGING_INBOUND_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog.catalog.adapter.inbound.messaging..",
        "io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging..",
        "io.github.ande1922.moduvera.reference.order.adapter.inbound.messaging.."
    };
    private static final String[] BUSINESS_INBOUND_ADAPTER_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog..adapter.inbound..",
        "io.github.ande1922.moduvera.reference.inventory..adapter.inbound..",
        "io.github.ande1922.moduvera.reference.order..adapter.inbound.."
    };
    private static final String[] BUSINESS_OUTBOUND_ADAPTER_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog..adapter.outbound..",
        "io.github.ande1922.moduvera.reference.inventory..adapter.outbound..",
        "io.github.ande1922.moduvera.reference.order..adapter.outbound.."
    };
    private static final String[] BUSINESS_ADAPTER_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog..adapter..",
        "io.github.ande1922.moduvera.reference.inventory..adapter..",
        "io.github.ande1922.moduvera.reference.order..adapter.."
    };
    private static final String[] BUSINESS_GENERIC_TOP_LEVEL_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog.configuration..",
        "io.github.ande1922.moduvera.reference.catalog.infrastructure..",
        "io.github.ande1922.moduvera.reference.inventory.configuration..",
        "io.github.ande1922.moduvera.reference.inventory.infrastructure..",
        "io.github.ande1922.moduvera.reference.order.configuration..",
        "io.github.ande1922.moduvera.reference.order.infrastructure.."
    };
    private static final String[] APP_ASSEMBLY_PACKAGES = {
        "io.github.ande1922.moduvera.reference.app.catalog..",
        "io.github.ande1922.moduvera.reference.app.inventory..",
        "io.github.ande1922.moduvera.reference.app.order..",
        "io.github.ande1922.moduvera.reference.app.monolith.."
    };
    private static final String GATEWAY_PACKAGE = "io.github.ande1922.moduvera.reference.app.gateway..";
    private static final String[] BUSINESS_APPLICATION_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog.catalog.application..",
        "io.github.ande1922.moduvera.reference.inventory.application..",
        "io.github.ande1922.moduvera.reference.order.application.."
    };
    private static final String[] BUSINESS_CORE_PACKAGES = {
        "io.github.ande1922.moduvera.reference.catalog.catalog.application..",
        "io.github.ande1922.moduvera.reference.catalog.catalog.domain..",
        "io.github.ande1922.moduvera.reference.inventory.application..",
        "io.github.ande1922.moduvera.reference.inventory.domain..",
        "io.github.ande1922.moduvera.reference.order.application..",
        "io.github.ande1922.moduvera.reference.order.domain.."
    };
    private static final String REQUEST_MAPPING =
            "org.springframework.web.bind.annotation.RequestMapping";
    private static final String REST_CONTROLLER =
            "org.springframework.web.bind.annotation.RestController";
    private static final String RESERVE_INVENTORY_COMMAND =
            "io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand";
    private static final String COMMAND_MESSAGE_HANDLER =
            "io.github.ande1922.moduvera.message.handler.CommandMessageHandler";
    private static final String EVENT_MESSAGE_HANDLER =
            "io.github.ande1922.moduvera.message.handler.EventMessageHandler";
    private static final String INBOUND_MESSAGE_HANDLER =
            "io.github.ande1922.moduvera.message.handler.InboundMessageHandler";
    static final String MESSAGING_MIGRATION_CONFIGURATION =
            "io.github.ande1922.moduvera.messaging.kafka.migration.ModuveraMessagingMigrationConfiguration";
    static final DescribedPredicate<JavaClass> INVENTORY_RESERVATION_MESSAGE_ADAPTER =
            JavaClass.Predicates.resideInAPackage(
                            "io.github.ande1922.moduvera.reference.inventory.adapter.inbound.messaging..")
                    .and(DescribedPredicate.describe(
                            "depend on the provider-owned Reserve Inventory Command",
                            javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                                    .anyMatch(dependency -> dependency
                                            .getTargetClass()
                                            .getName()
                                            .equals(RESERVE_INVENTORY_COMMAND))))
                    .as("Inventory messaging inbound classes that consume the Reserve Inventory Command");
    private static final DescribedPredicate<JavaClass> APP_ASSEMBLY_TRANSPORT_MECHANIC =
            DescribedPredicate.describe(
                    "transport mechanics other than the explicit messaging platform migration definition",
                    javaClass -> residesIn(javaClass, "org.springframework.messaging")
                            || residesIn(javaClass, "io.github.ande1922.moduvera.message")
                            || residesIn(javaClass, "tools.jackson")
                            || (residesIn(javaClass, "io.github.ande1922.moduvera.messaging.kafka")
                                    && !javaClass.getName().equals(MESSAGING_MIGRATION_CONFIGURATION)));
    private static final DescribedPredicate<JavaClass> RELIABLE_INBOUND_MECHANIC =
            DescribedPredicate.describe(
                    "Inbox, retry, trusted-context or reliable-endpoint mechanics",
                    javaClass -> javaClass.getPackageName()
                                    .startsWith("io.github.ande1922.moduvera.message.inbox")
                            || javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.context.ExecutionContextHolder")
                            || javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint")
                            || javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory")
                            || javaClass.getName().equals("java.lang.Thread")
                            || javaClass.getName().equals("java.util.concurrent.TimeUnit")
                            || residesIn(javaClass, "org.springframework.retry")
                            || residesIn(javaClass, "reactor.util.retry"));
    private static final DescribedPredicate<JavaClass> APP_ASSEMBLY_MIGRATION_EXECUTOR =
            DescribedPredicate.describe(
                    "migration execution rather than a selected migration definition",
                    javaClass -> javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.migration.DatabaseMigrator")
                            || javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.migration.MigrationPlan"));
    private static final DescribedPredicate<JavaClass> DEPRECATED_BUSINESS_CONFIGURATION_FACADE =
            DescribedPredicate.describe(
                    "a deprecated Business Service configuration facade",
                    javaClass -> javaClass.getName()
                            .equals("io.github.ande1922.moduvera.reference.catalog.CatalogModuleConfiguration"));
    private static final DescribedPredicate<JavaClass> IMPLEMENTS_INBOUND_MESSAGE_HANDLER =
            DescribedPredicate.describe(
                    "implement InboundMessageHandler",
                    javaClass -> javaClass.isAssignableTo(INBOUND_MESSAGE_HANDLER));
    private static final DescribedPredicate<JavaClass> BUSINESS_INBOUND_MESSAGE_HANDLER =
            JavaClass.Predicates.resideInAnyPackage(BUSINESS_SERVICE_PACKAGES)
                    .and(IMPLEMENTS_INBOUND_MESSAGE_HANDLER)
                    .as("Business Service classes that implement InboundMessageHandler");
    private static final DescribedPredicate<JavaClass> BUSINESS_MESSAGING_HANDLER =
            JavaClass.Predicates.resideInAnyPackage(BUSINESS_MESSAGING_INBOUND_PACKAGES)
                    .and(IMPLEMENTS_INBOUND_MESSAGE_HANDLER)
                    .as("Business Service messaging classes that implement InboundMessageHandler");
    private static final ArchCondition<JavaClass> HAVE_EXPLICIT_BUSINESS_OWNER =
            new ArchCondition<>("belong to an explicit Business Module, API, migration slice or compatibility facade") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean owned = hasExplicitBusinessOwner(item);
                    events.add(new SimpleConditionEvent(
                            item,
                            owned,
                            item.getName()
                                    + " must belong to an explicit Business Module, API, migration slice or compatibility facade"));
                }
            };
    private static final ArchCondition<JavaClass> IMPLEMENT_COMMAND_OR_EVENT_HANDLER =
            new ArchCondition<>("implement CommandMessageHandler or EventMessageHandler") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean classified = item.getAllRawInterfaces().stream()
                            .map(JavaClass::getName)
                            .anyMatch(interfaceName -> interfaceName.equals(COMMAND_MESSAGE_HANDLER)
                                    || interfaceName.equals(EVENT_MESSAGE_HANDLER));
                    events.add(new SimpleConditionEvent(
                            item,
                            classified,
                            item.getName()
                                    + " must implement CommandMessageHandler or EventMessageHandler"));
                }
            };

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

    public static final ArchRule BUSINESS_SERVICES_AVOID_GENERIC_TOP_LEVEL_PACKAGES = noClasses()
            .should()
            .resideInAnyPackage(BUSINESS_GENERIC_TOP_LEVEL_PACKAGES)
            .as("Business Services must use module and adapter ownership instead of top-level configuration or infrastructure packages");

    public static final ArchRule BUSINESS_SERVICE_CLASSES_HAVE_EXPLICIT_OWNERS = classes()
            .that()
            .resideInAnyPackage(BUSINESS_SERVICE_PACKAGES)
            .should(HAVE_EXPLICIT_BUSINESS_OWNER)
            .as("Every Business Service class must belong to an explicit owned surface");

    public static final ArchRule BUSINESS_CORE_DOES_NOT_DEPEND_ON_ADAPTERS = noClasses()
            .that()
            .resideInAnyPackage(BUSINESS_CORE_PACKAGES)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(BUSINESS_ADAPTER_PACKAGES)
            .as("Business Application and Domain code must depend on ports rather than concrete Adapters");

    public static final ArchRule BUSINESS_INBOUND_ADAPTERS_DO_NOT_DEPEND_ON_OUTBOUND = noClasses()
            .that()
            .resideInAnyPackage(BUSINESS_INBOUND_ADAPTER_PACKAGES)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(BUSINESS_OUTBOUND_ADAPTER_PACKAGES)
            .as("Inbound business Adapters must collaborate with Outbound Adapters through core ports");

    public static final ArchRule BUSINESS_OUTBOUND_ADAPTERS_DO_NOT_DEPEND_ON_INBOUND = noClasses()
            .that()
            .resideInAnyPackage(BUSINESS_OUTBOUND_ADAPTER_PACKAGES)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(BUSINESS_INBOUND_ADAPTER_PACKAGES)
            .as("Outbound business Adapters must not depend on Inbound Adapters");

    public static final ArchRule BUSINESS_ADAPTER_DIRECTIONS_DO_NOT_CROSS =
            CompositeArchRule.of(BUSINESS_INBOUND_ADAPTERS_DO_NOT_DEPEND_ON_OUTBOUND)
                    .and(BUSINESS_OUTBOUND_ADAPTERS_DO_NOT_DEPEND_ON_INBOUND)
                    .as("Inbound and Outbound business Adapters must collaborate through protocol-neutral application or domain seams");

    public static final ArchRule MODULE_CONFIGURATIONS_DO_NOT_ACTIVATE_ADAPTERS = noClasses()
            .that()
            .resideInAnyPackage(BUSINESS_SERVICE_PACKAGES)
            .and()
            .haveSimpleNameEndingWith("ModuleConfiguration")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(BUSINESS_ADAPTER_PACKAGES)
            .as("Business Module Configuration constructs Application Services but must not activate Adapters");

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
                            .dependOnClassesThat(APP_ASSEMBLY_TRANSPORT_MECHANIC))
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
                            .haveSimpleNameEndingWith("Mapper"))
                    .and(noClasses()
                            .that()
                            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
                            .should()
                            .haveSimpleNameEndingWith("MessageHandler"))
                    .as("App Assemblies must only select and activate business inbound adapters");

    public static final ArchRule APP_ASSEMBLIES_DO_NOT_EXECUTE_MIGRATIONS = noClasses()
            .that()
            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
            .should()
            .dependOnClassesThat(APP_ASSEMBLY_MIGRATION_EXECUTOR)
            .as("App Assemblies select migration definitions and policy but must not construct migration executors or plans");

    public static final ArchRule APP_ASSEMBLIES_DO_NOT_SELECT_DEPRECATED_BUSINESS_FACADES = noClasses()
            .that()
            .resideInAnyPackage(APP_ASSEMBLY_PACKAGES)
            .should()
            .dependOnClassesThat(DEPRECATED_BUSINESS_CONFIGURATION_FACADE)
            .as("Current App Assemblies must select explicit Module and Adapter slices instead of deprecated service-level facades");

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

    public static final ArchRule BUSINESS_MESSAGE_HANDLERS_BELONG_TO_PROVIDER_INBOUND = classes()
            .that(BUSINESS_INBOUND_MESSAGE_HANDLER)
            .should()
            .resideInAnyPackage(BUSINESS_MESSAGING_INBOUND_PACKAGES)
            .as("Business InboundMessageHandler implementations must reside in provider adapter.inbound.messaging packages")
            .allowEmptyShould(true);

    public static final ArchRule BUSINESS_MESSAGE_INBOUND_BELONGS_TO_PROVIDER_ADAPTER =
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
                                    "io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint",
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
                    .and(BUSINESS_MESSAGE_HANDLERS_BELONG_TO_PROVIDER_INBOUND)
                    .as("Business Service message endpoints, handlers and payload mappers must reside in the provider adapter.inbound.messaging package");

    public static final ArchRule BUSINESS_MESSAGE_HANDLERS_ARE_CLASSIFIED = classes()
            .that(BUSINESS_MESSAGING_HANDLER)
            .should(IMPLEMENT_COMMAND_OR_EVENT_HANDLER)
            .as("Concrete business Message Handlers must declare Command or Event semantics");

    public static final ArchRule BUSINESS_MESSAGE_HANDLERS_ARE_PACKAGE_PRIVATE = noClasses()
            .that(BUSINESS_MESSAGING_HANDLER)
            .should()
            .bePublic()
            .as("Concrete business Message Handlers must be package-private");

    public static final ArchRule BUSINESS_MESSAGE_HANDLERS_HAVE_EXPLICIT_NAMES = classes()
            .that(BUSINESS_MESSAGING_HANDLER)
            .should()
            .haveSimpleNameEndingWith("MessageHandler")
            .as("Concrete business Message Handlers must use an explicit MessageHandler name");

    public static final ArchRule BUSINESS_MESSAGE_HANDLERS_ARE_CLASSIFIED_AND_PACKAGE_PRIVATE =
            CompositeArchRule.of(BUSINESS_MESSAGE_HANDLERS_ARE_CLASSIFIED)
                    .and(BUSINESS_MESSAGE_HANDLERS_ARE_PACKAGE_PRIVATE)
                    .and(BUSINESS_MESSAGE_HANDLERS_HAVE_EXPLICIT_NAMES)
                    .as("Concrete business Message Handlers must be package-private and classified as Command or Event handlers");

    public static final ArchRule APPLICATION_SERVICES_DO_NOT_IMPLEMENT_MESSAGE_HANDLERS = noClasses()
            .that()
            .resideInAnyPackage(BUSINESS_APPLICATION_PACKAGES)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.github.ande1922.moduvera.message.handler..")
            .as("Application Services expose business use cases and must not implement transport Message Handler interfaces");

    public static final ArchRule BUSINESS_MESSAGE_HANDLERS_DO_NOT_OWN_RELIABILITY = noClasses()
            .that(BUSINESS_MESSAGING_HANDLER)
            .should()
            .dependOnClassesThat(RELIABLE_INBOUND_MECHANIC)
            .as("ReliableInboundEndpoint owns Inbox, retry and trusted-context mechanics outside concrete business Message Handlers");

    public static ArchRule asyncOnlyCapabilityDoesNotExposeSynchronousServiceApi(
            Class<?> commandType) {
        String commandName = commandType.getName();
        String providerApiPackage = providerApiPackage(commandType);
        DescribedPredicate<JavaMethod> acceptsCommand = DescribedPredicate.describe(
                "accept the asynchronous-only command " + commandName,
                method -> method.getParameterTypes().stream()
                        .flatMap(parameter -> parameter.getAllInvolvedRawTypes().stream())
                        .anyMatch(parameter -> parameter.getName().equals(commandName)));
        ArchCondition<JavaClass> notExposeSynchronousServiceApi = new ArchCondition<>(
                "not expose the asynchronous-only command " + commandName
                        + " through a public Service API interface") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!javaClass.isInterface()
                        || !javaClass.getModifiers().contains(JavaModifier.PUBLIC)) {
                    return;
                }
                javaClass.getMethods().stream()
                        .filter(acceptsCommand::test)
                        .forEach(method -> events.add(SimpleConditionEvent.violated(
                                javaClass,
                                method.getFullName()
                                        + " exposes asynchronous-only command "
                                        + commandName)));
            }
        };
        return classes()
                .that()
                .resideInAPackage(providerApiPackage + "..")
                .should(notExposeSynchronousServiceApi)
                .as("an asynchronous-only command must not be exposed through a synchronous public Service API");
    }

    private static String providerApiPackage(Class<?> commandType) {
        String packageName = commandType.getPackageName();
        String marker = ".api";
        int markerIndex = packageName.indexOf(marker);
        boolean completeSegment = markerIndex >= 0
                && (markerIndex + marker.length() == packageName.length()
                        || packageName.charAt(markerIndex + marker.length()) == '.');
        if (!completeSegment) {
            throw new IllegalArgumentException(
                    "asynchronous-only command must reside below a provider api package: "
                            + commandType.getName());
        }
        return packageName.substring(0, markerIndex + marker.length());
    }

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

    private static boolean residesIn(JavaClass javaClass, String packageName) {
        return javaClass.getPackageName().equals(packageName)
                || javaClass.getPackageName().startsWith(packageName + ".");
    }

    private static boolean hasExplicitBusinessOwner(JavaClass javaClass) {
        String className = javaClass.getName();
        return className.equals("io.github.ande1922.moduvera.reference.catalog.CatalogModuleConfiguration")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.catalog.api")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.catalog.catalog")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.catalog.migration")
                || className.equals("io.github.ande1922.moduvera.reference.inventory.InventoryModuleConfiguration")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.inventory.api")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.inventory.application")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.inventory.domain")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.inventory.adapter")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.inventory.migration")
                || className.equals("io.github.ande1922.moduvera.reference.order.OrderModuleConfiguration")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.order.api")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.order.application")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.order.domain")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.order.adapter")
                || startsWithPackage(className, "io.github.ande1922.moduvera.reference.order.migration");
    }

    private static boolean startsWithPackage(String className, String packageName) {
        return className.startsWith(packageName + ".");
    }

    private ModuveraArchitectureRules() {}
}
