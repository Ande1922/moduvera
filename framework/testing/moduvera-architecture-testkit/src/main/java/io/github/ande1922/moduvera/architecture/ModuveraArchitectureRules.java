package io.github.ande1922.moduvera.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

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
    private static final String APPLICATION_MESSAGE_HANDLER =
            "io.github.ande1922.moduvera.message.handler.ApplicationMessageHandler";
    private static final String INBOX_TEMPLATE =
            "io.github.ande1922.moduvera.message.inbox.InboxTemplate";
    private static final String TRANSACTION_BOUNDARY =
            "io.github.ande1922.moduvera.data.TransactionBoundary";
    static final String MESSAGING_MIGRATION_CONFIGURATION =
            "io.github.ande1922.moduvera.messaging.kafka.migration.ModuveraMessagingMigrationConfiguration";
    static final DescribedPredicate<JavaClass> INVENTORY_RESERVATION_APPLICATION_HANDLER =
            JavaClass.Predicates.resideInAPackage(
                            "io.github.ande1922.moduvera.reference.inventory.application..")
                    .and(DescribedPredicate.describe(
                            "implement ApplicationMessageHandler",
                            javaClass -> !javaClass.isInterface()
                                    && javaClass.isAssignableTo(APPLICATION_MESSAGE_HANDLER)))
                    .and(DescribedPredicate.describe(
                            "depend on the provider-owned Reserve Inventory Command",
                            javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                                    .anyMatch(dependency -> dependency
                                            .getTargetClass()
                                            .getName()
                                            .equals(RESERVE_INVENTORY_COMMAND))))
                    .as("Inventory application message Handlers that consume the Reserve Inventory Command");
    private static final DescribedPredicate<JavaClass> APP_ASSEMBLY_TRANSPORT_MECHANIC =
            DescribedPredicate.describe(
                    "transport mechanics other than the explicit messaging platform migration definition",
                    javaClass -> residesIn(javaClass, "org.springframework.messaging")
                            || residesIn(javaClass, "io.github.ande1922.moduvera.message")
                            || residesIn(javaClass, "tools.jackson")
                            || (residesIn(javaClass, "io.github.ande1922.moduvera.messaging.kafka")
                                    && !javaClass.getName().equals(MESSAGING_MIGRATION_CONFIGURATION)));
    private static final DescribedPredicate<JavaClass> APPLICATION_MESSAGE_HANDLER_IMPLEMENTATION =
            DescribedPredicate.describe(
                    "implement ApplicationMessageHandler",
                    javaClass -> !javaClass.isInterface()
                            && javaClass.isAssignableTo(APPLICATION_MESSAGE_HANDLER));
    private static final DescribedPredicate<JavaClass> MESSAGE_TRANSPORT_TYPE =
            DescribedPredicate.describe(
                    "message transport or serialized-envelope types",
                    javaClass -> javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.message.SerializedMessage")
                            || residesIn(javaClass, "org.springframework.messaging")
                            || residesIn(javaClass, "org.springframework.cloud.stream")
                            || residesIn(javaClass, "org.apache.kafka")
                            || residesIn(javaClass, "org.apache.rocketmq")
                            || residesIn(javaClass, "io.github.ande1922.moduvera.messaging.kafka"));
    private static final DescribedPredicate<JavaClass> INBOX_OR_TRANSACTION_BOUNDARY =
            DescribedPredicate.describe(
                    "Inbox or top-level transaction control",
                    javaClass -> javaClass.getPackageName()
                                    .startsWith("io.github.ande1922.moduvera.message.inbox")
                            || javaClass.getName().equals(TRANSACTION_BOUNDARY));
    private static final DescribedPredicate<JavaClass> RELIABLE_CONSUMER_TRANSPORT =
            DescribedPredicate.describe(
                    "reliable consumer transport implementation",
                    javaClass -> javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.messaging.kafka.ReliableInboundEndpoint")
                            || javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.messaging.kafka.ReliableMessageConsumerFactory"));
    private static final DescribedPredicate<JavaClass> APP_ASSEMBLY_MIGRATION_EXECUTOR =
            DescribedPredicate.describe(
                    "migration execution rather than a selected migration definition",
                    javaClass -> javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.migration.DatabaseMigrator")
                            || javaClass.getName()
                                    .equals("io.github.ande1922.moduvera.migration.MigrationPlan"));
    private static final ArchCondition<JavaClass> HAVE_EXPLICIT_BUSINESS_OWNER =
            new ArchCondition<>("belong to an explicit Business Module, API or migration slice") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean owned = hasExplicitBusinessOwner(item);
                    events.add(new SimpleConditionEvent(
                            item,
                            owned,
                            item.getName()
                                    + " must belong to an explicit Business Module, API or migration slice"));
                }
            };
    private static final ArchCondition<JavaClass> DEPEND_ON_INBOX_TEMPLATE =
            new ArchCondition<>("depend directly on InboxTemplate") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean ownsInbox = item.getDirectDependenciesFromSelf().stream()
                            .anyMatch(dependency -> dependency.getTargetClass().getName().equals(INBOX_TEMPLATE));
                    events.add(new SimpleConditionEvent(
                            item,
                            ownsInbox,
                            item.getName() + " must depend directly on InboxTemplate"));
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
                    .as("Business Service message consumer configurations and payload mappers must reside in the provider adapter.inbound.messaging package");

    public static final ArchRule APPLICATION_MESSAGE_HANDLERS_BELONG_TO_APPLICATION = classes()
            .that(APPLICATION_MESSAGE_HANDLER_IMPLEMENTATION)
            .should()
            .resideInAPackage("..application..")
            .as("ApplicationMessageHandler implementations must reside in an application package");

    public static final ArchRule APPLICATION_MESSAGE_HANDLERS_HAVE_EXPLICIT_NAMES = classes()
            .that(APPLICATION_MESSAGE_HANDLER_IMPLEMENTATION)
            .should()
            .haveSimpleNameEndingWith("Handler")
            .as("ApplicationMessageHandler implementations must be independent Handler types");

    public static final ArchRule APPLICATION_MESSAGE_HANDLERS_OWN_INBOX = classes()
            .that(APPLICATION_MESSAGE_HANDLER_IMPLEMENTATION)
            .should(DEPEND_ON_INBOX_TEMPLATE)
            .as("ApplicationMessageHandler implementations own their fixed Inbox use-case boundary");

    public static final ArchRule APPLICATION_MESSAGE_HANDLERS_ARE_PROTOCOL_NEUTRAL = noClasses()
            .that(APPLICATION_MESSAGE_HANDLER_IMPLEMENTATION)
            .should()
            .dependOnClassesThat(MESSAGE_TRANSPORT_TYPE)
            .as("ApplicationMessageHandler implementations accept decoded provider payloads and MessageId without transport envelopes");

    public static final ArchRule APPLICATION_MESSAGE_HANDLERS_DO_NOT_OWN_TRANSACTION_BOUNDARY = noClasses()
            .that(APPLICATION_MESSAGE_HANDLER_IMPLEMENTATION)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(TRANSACTION_BOUNDARY)
            .as("ApplicationMessageHandler implementations use InboxTemplate as the unique complete local transaction boundary");

    public static final ArchRule APPLICATION_MESSAGE_HANDLERS_HAVE_APPLICATION_OWNERSHIP =
            CompositeArchRule.of(APPLICATION_MESSAGE_HANDLERS_BELONG_TO_APPLICATION)
                    .and(APPLICATION_MESSAGE_HANDLERS_HAVE_EXPLICIT_NAMES)
                    .and(APPLICATION_MESSAGE_HANDLERS_OWN_INBOX)
                    .and(APPLICATION_MESSAGE_HANDLERS_ARE_PROTOCOL_NEUTRAL)
                    .and(APPLICATION_MESSAGE_HANDLERS_DO_NOT_OWN_TRANSACTION_BOUNDARY)
                    .as("Application message Handlers own protocol-neutral use cases and Inbox without top-level transaction control");

    public static final ArchRule BUSINESS_MESSAGE_INBOUND_DOES_NOT_OWN_INBOX_TRANSACTION = noClasses()
            .that()
            .resideInAnyPackage(BUSINESS_MESSAGING_INBOUND_PACKAGES)
            .should()
            .dependOnClassesThat(INBOX_OR_TRANSACTION_BOUNDARY)
            .as("Business messaging inbound adapters decode and invoke handlers without owning Inbox or business transactions");

    public static final ArchRule RELIABLE_CONSUMER_TRANSPORT_DOES_NOT_OWN_BUSINESS_TRANSACTION = noClasses()
            .that(RELIABLE_CONSUMER_TRANSPORT)
            .should()
            .dependOnClassesThat(INBOX_OR_TRANSACTION_BOUNDARY)
            .as("Reliable consumer transport validates envelopes, restores trusted context and retries without owning Inbox or business transactions");

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
        return startsWithPackage(className, "io.github.ande1922.moduvera.reference.catalog.api")
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
