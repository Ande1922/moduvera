package io.github.ande1922.moduvera.security.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * App-owned selection of handlers that receive an HTTP {@code ExecutionContext}.
 *
 * <p>Selection is independent of Spring Security request authorization. Exclusions win over
 * managed selectors and only disable this context adapter.
 */
public final class ExecutionContextHandlerSelection {

    private final List<Predicate<Class<?>>> managed;
    private final List<Predicate<Class<?>>> excluded;

    private ExecutionContextHandlerSelection(
            List<Predicate<Class<?>>> managed, List<Predicate<Class<?>>> excluded) {
        this.managed = List.copyOf(managed);
        this.excluded = List.copyOf(excluded);
    }

    public static Builder builder() {
        return new Builder();
    }

    boolean includes(HandlerMethod handler) {
        Class<?> handlerType = ClassUtils.getUserClass(handler.getBeanType());
        return excluded.stream().noneMatch(selector -> selector.test(handlerType))
                && managed.stream().anyMatch(selector -> selector.test(handlerType));
    }

    public static final class Builder {

        private final List<Predicate<Class<?>>> managed = new ArrayList<>();
        private final List<Predicate<Class<?>>> excluded = new ArrayList<>();

        private Builder() {}

        public Builder manageHandlers(Class<?>... handlerTypes) {
            managed.add(types(handlerTypes));
            return this;
        }

        public Builder managePackage(String packageName) {
            managed.add(inPackage(packageName));
            return this;
        }

        public Builder excludeHandlers(Class<?>... handlerTypes) {
            excluded.add(types(handlerTypes));
            return this;
        }

        public Builder excludePackage(String packageName) {
            excluded.add(inPackage(packageName));
            return this;
        }

        public ExecutionContextHandlerSelection build() {
            if (managed.isEmpty()) {
                throw new IllegalStateException("at least one managed HTTP handler selector is required");
            }
            return new ExecutionContextHandlerSelection(managed, excluded);
        }

        private static Predicate<Class<?>> types(Class<?>... handlerTypes) {
            Objects.requireNonNull(handlerTypes, "handlerTypes");
            List<Class<?>> types = List.of(handlerTypes);
            types.forEach(type -> Objects.requireNonNull(type, "handlerType"));
            if (types.isEmpty()) {
                throw new IllegalArgumentException("at least one handler type is required");
            }
            return candidate -> types.stream().anyMatch(type -> type.isAssignableFrom(candidate));
        }

        private static Predicate<Class<?>> inPackage(String packageName) {
            Objects.requireNonNull(packageName, "packageName");
            if (packageName.isBlank()) {
                throw new IllegalArgumentException("packageName must not be blank");
            }
            String packagePrefix = packageName + '.';
            return candidate -> candidate.getPackageName().equals(packageName)
                    || candidate.getPackageName().startsWith(packagePrefix);
        }
    }
}
