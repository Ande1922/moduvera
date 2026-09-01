package io.github.ande1922.moduvera.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.ande1922.moduvera.architecturefixture.contract.api.PartialMessageContract;
import io.github.ande1922.moduvera.architecturefixture.contract.api.SampleMessageContract;
import io.github.ande1922.moduvera.message.Destination;
import io.github.ande1922.moduvera.message.MessageKind;
import io.github.ande1922.moduvera.message.MessageType;
import io.github.ande1922.moduvera.reference.inventory.api.InventoryReservationResult;
import io.github.ande1922.moduvera.reference.inventory.api.ReserveInventoryCommand;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RepositoryMessageContractTest {

    private static final Set<String> IDENTITY_FIELDS =
            Set.of("MESSAGE_KIND", "MESSAGE_TYPE", "DESTINATION");
    private static final Map<Class<?>, StableIdentity> REGISTERED_IDENTITIES = Map.of(
            ReserveInventoryCommand.class,
            new StableIdentity(
                    "ASYNC_COMMAND",
                    "io.github.ande1922.moduvera.reference.inventory.reserve.v1",
                    "inventory.reserve"),
            InventoryReservationResult.class,
            new StableIdentity(
                    "EVENT",
                    "io.github.ande1922.moduvera.reference.inventory.reservation-result.v1",
                    "order.inventory-result"));
    private static final JavaClasses CURRENT_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ande1922.moduvera");

    @Test
    void everyProviderOwnedMessageIdentityIsRegisteredAndStable() {
        assertStableMessageContracts(CURRENT_CLASSES, REGISTERED_IDENTITIES);
    }

    @Test
    void rejectsPartialIdentityDeclarations() {
        JavaClasses partial = new ClassFileImporter().importClasses(PartialMessageContract.class);

        assertThatThrownBy(() -> assertStableMessageContracts(partial, Map.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(PartialMessageContract.class.getName(), "MESSAGE_TYPE", "DESTINATION");
    }

    @Test
    void rejectsUnregisteredOrDriftingIdentities() {
        JavaClasses sample = new ClassFileImporter().importClasses(SampleMessageContract.class);

        assertThatThrownBy(() -> assertStableMessageContracts(sample, Map.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("registered message contract owners");
        assertThatThrownBy(() -> assertStableMessageContracts(
                        sample,
                        Map.of(
                                SampleMessageContract.class,
                                new StableIdentity(
                                        "EVENT",
                                        "io.github.ande1922.moduvera.sample.changed.v1",
                                        "sample.messages"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("MESSAGE_TYPE");
    }

    private static void assertStableMessageContracts(
            JavaClasses classes, Map<Class<?>, StableIdentity> registered) {
        Map<String, JavaClass> discovered = discoverIdentityOwners(classes);
        Set<String> registeredOwners = registered.keySet().stream()
                .map(Class::getName)
                .collect(Collectors.toSet());
        assertThat(discovered.keySet())
                .as("registered message contract owners")
                .containsExactlyInAnyOrderElementsOf(registeredOwners);

        registered.forEach((owner, expected) -> {
            assertThat(classes.contain(owner.getName()))
                    .as("architecture classpath contains %s", owner.getName())
                    .isTrue();
            JavaClass javaClass = discovered.get(owner.getName());
            assertIdentityField(javaClass, "MESSAGE_KIND");
            assertIdentityField(javaClass, "MESSAGE_TYPE");
            assertIdentityField(javaClass, "DESTINATION");
            StableIdentity actual = readIdentity(owner);
            assertThat(actual.kind()).as("%s MESSAGE_KIND", owner.getName()).isEqualTo(expected.kind());
            assertThat(actual.type()).as("%s MESSAGE_TYPE", owner.getName()).isEqualTo(expected.type());
            assertThat(actual.destination())
                    .as("%s DESTINATION", owner.getName())
                    .isEqualTo(expected.destination());
            assertThatCode(() -> MessageKind.valueOf(actual.kind()))
                    .as("%s MESSAGE_KIND is supported", owner.getName())
                    .doesNotThrowAnyException();
            assertThatCode(() -> new MessageType(actual.type()))
                    .as("%s MESSAGE_TYPE is valid", owner.getName())
                    .doesNotThrowAnyException();
            assertThatCode(() -> new Destination(actual.destination()))
                    .as("%s DESTINATION is valid", owner.getName())
                    .doesNotThrowAnyException();
            assertThat(actual.type())
                    .as("%s MESSAGE_TYPE is explicitly versioned", owner.getName())
                    .matches(".+\\.v[1-9][0-9]*");
        });
        assertThat(registered.values())
                .extracting(StableIdentity::type)
                .as("message types are unique across registered contracts")
                .doesNotHaveDuplicates();
    }

    private static Map<String, JavaClass> discoverIdentityOwners(JavaClasses classes) {
        var discovered = new LinkedHashMap<String, JavaClass>();
        classes.stream()
                .filter(javaClass -> isApiPackage(javaClass.getPackageName()))
                .forEach(javaClass -> {
                    Set<String> fields = javaClass.getFields().stream()
                            .map(JavaField::getName)
                            .collect(Collectors.toSet());
                    if (fields.stream().anyMatch(IDENTITY_FIELDS::contains)) {
                        assertThat(fields)
                                .as("%s declares the complete message identity", javaClass.getName())
                                .containsAll(IDENTITY_FIELDS);
                        discovered.put(javaClass.getName(), javaClass);
                    }
                });
        return discovered;
    }

    private static boolean isApiPackage(String packageName) {
        return packageName.equals("api")
                || packageName.startsWith("api.")
                || packageName.endsWith(".api")
                || packageName.contains(".api.");
    }

    private static void assertIdentityField(JavaClass owner, String fieldName) {
        JavaField field = owner.getField(fieldName);
        assertThat(field.getRawType().getName())
                .as("%s %s type", owner.getName(), fieldName)
                .isEqualTo(String.class.getName());
        assertThat(field.getModifiers())
                .as("%s %s modifiers", owner.getName(), fieldName)
                .contains(JavaModifier.PUBLIC, JavaModifier.STATIC, JavaModifier.FINAL);
    }

    private static StableIdentity readIdentity(Class<?> owner) {
        return new StableIdentity(
                readStringConstant(owner, "MESSAGE_KIND"),
                readStringConstant(owner, "MESSAGE_TYPE"),
                readStringConstant(owner, "DESTINATION"));
    }

    private static String readStringConstant(Class<?> owner, String fieldName) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            assertThat(field.getType()).as("%s %s type", owner.getName(), fieldName).isEqualTo(String.class);
            assertThat(field.getModifiers())
                    .as("%s %s reflection modifiers", owner.getName(), fieldName)
                    .matches(modifiers -> Modifier.isPublic(modifiers)
                            && Modifier.isStatic(modifiers)
                            && Modifier.isFinal(modifiers));
            return (String) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "cannot read message identity field " + owner.getName() + "." + fieldName,
                    failure);
        }
    }

    private record StableIdentity(String kind, String type, String destination) {}
}
