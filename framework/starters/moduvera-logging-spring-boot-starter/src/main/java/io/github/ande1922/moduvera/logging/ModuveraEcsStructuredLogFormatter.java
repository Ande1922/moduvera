package io.github.ande1922.moduvera.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** ECS formatter that merges a stable error code with a privacy-safe throwable projection. */
public final class ModuveraEcsStructuredLogFormatter
        implements StructuredLogFormatter<ILoggingEvent> {

    private static final String ECS_VERSION = "8.11";
    private static final String DEFAULT_ERROR_CODE = "SYS_UNEXPECTED";
    private static final int MAX_TEXT_LENGTH = 2048;
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final int MAX_STACK_FRAMES_PER_CAUSE = 64;
    private static final Pattern FIELD_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)*");
    private static final Pattern ERROR_CODE =
            Pattern.compile("(?:BIZ|DEP|SYS|ENV)_[A-Z0-9]+(?:_[A-Z0-9]+)*");
    private static final String SENSITIVE_NAME_PART =
            "(?:x[._-]*)?api[._-]*key|pass[._-]*word|passwd|"
                    + "secret|credential|authorization|cookie|headers?|body|payload|query|sql|token";
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "[A-Za-z0-9_.-]*(?:" + SENSITIVE_NAME_PART + ")[A-Za-z0-9_.-]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "[\"']?(" + SENSITIVE_NAME.pattern() + ")"
                    + "[\"']?\\s*[:=]\\s*(?:(?:bearer|basic)\\s+[^\\s,;}\\]]+|[\"'][^\"']*[\"']|(?!\\{\\})[^\\s,;}\\]]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s,;]+");
    private static final Set<String> RESERVED_ROOTS =
            Set.of("log", "process", "service", "ecs", "message", "tags", "error");
    private static final Set<String> NESTED_ROOTS =
            Set.of("event", "retry", "http", "messaging", "url", "db");
    private static final Set<String> SAFE_SIZE_FIELDS = Set.of(
            "http.request.body.bytes",
            "http.response.body.bytes",
            "messaging.message.body.size");
    private static final Set<String> EVENT_OUTCOMES = Set.of("success", "failure", "unknown");
    private static final Set<String> DISPOSITIONS = Set.of("retry", "dead_letter");
    private static final Set<String> GOVERNED_SCALAR_FIELDS = Set.of(
            "event.action",
            "event.outcome",
            "duration_ms",
            "retry.attempt",
            "retry.max_attempts",
            "disposition",
            "http.request.body.bytes",
            "http.response.body.bytes",
            "messaging.message.body.size",
            "url.full",
            "http.url");
    private static final JsonWriter<Map<String, Object>> JSON_WRITER =
            JsonWriter.<Map<String, Object>>standard().withNewLineAtEnd();

    private final String serviceName;
    private final String serviceVersion;
    private final String serviceEnvironment;

    /** Creates the formatter using Spring Boot's application and ECS service properties. */
    public ModuveraEcsStructuredLogFormatter(Environment environment) {
        this.serviceName = firstText(
                environment,
                "logging.structured.ecs.service.name",
                "spring.application.name");
        this.serviceVersion = firstText(
                environment,
                "logging.structured.ecs.service.version",
                "spring.application.version");
        this.serviceEnvironment = serviceEnvironment(environment);
    }

    private static String firstText(Environment environment, String... names) {
        for (String name : names) {
            String value = environment.getProperty(name);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("@timestamp", event.getInstant().toString());
        root.put("log", mapOf("level", event.getLevel().toString(), "logger", event.getLoggerName()));
        root.put(
                "process",
                mapOf(
                        "pid",
                        ProcessHandle.current().pid(),
                        "thread",
                        mapOf("name", event.getThreadName())));
        Map<String, Object> service = mapOf(
                "name", serviceName,
                "version", serviceVersion,
                "environment", serviceEnvironment);
        if (!service.isEmpty()) {
            root.put("service", service);
        }
        root.put("message", sanitizeText(event.getFormattedMessage()));

        addStructuredFields(root, event.getKeyValuePairs(), pair -> pair.key, pair -> pair.value);
        Map<String, String> mdc = event.getMDCPropertyMap();
        addStructuredFields(
                root,
                mdc == null ? null : mdc.entrySet(),
                Map.Entry::getKey,
                Map.Entry::getValue);
        normalizeRetry(root);
        TrustedLogContext.currentFields().forEach(root::put);
        addError(root, event, mdc);
        addMarkers(root, event.getMarkerList());
        root.put("ecs", Map.of("version", ECS_VERSION));
        return JSON_WRITER.writeToString(root);
    }

    private static String serviceEnvironment(Environment environment) {
        String configured = environment.getProperty("logging.structured.ecs.service.environment");
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        String[] profiles = activeProfiles.length == 0 ? environment.getDefaultProfiles() : activeProfiles;
        return String.join(",", profiles);
    }

    private static <T> void addStructuredFields(
            Map<String, Object> root,
            Iterable<T> fields,
            Function<T, String> name,
            Function<T, Object> value) {
        if (fields == null) {
            return;
        }
        for (T field : fields) {
            addStructuredField(root, name.apply(field), value.apply(field));
        }
    }

    private static void addStructuredField(Map<String, Object> root, String name, Object value) {
        if (!isAllowedField(name)) {
            return;
        }
        Object safeValue = safeValue(name, value);
        if (safeValue == null) {
            return;
        }
        putNested(root, name, safeValue);
    }

    private static boolean isAllowedField(String name) {
        if (name == null || !FIELD_NAME.matcher(name).matches()) {
            return false;
        }
        if (isTrustedNamespace(name)
                || isGovernedScalarChild(name)
                || "error.code".equals(name)
                || RESERVED_ROOTS.contains(rootName(name))
                || NESTED_ROOTS.contains(name)) {
            return false;
        }
        return !isSensitiveName(name);
    }

    private static boolean isSensitiveName(String name) {
        if (SAFE_SIZE_FIELDS.contains(name)) {
            return false;
        }
        return SENSITIVE_NAME.matcher(name).matches();
    }

    private static boolean isTrustedNamespace(String name) {
        return TrustedLogContext.FIELD_NAMES.stream()
                .anyMatch(fieldName -> name.equals(fieldName) || name.startsWith(fieldName + "."));
    }

    private static boolean isGovernedScalarChild(String name) {
        return GOVERNED_SCALAR_FIELDS.stream().anyMatch(fieldName -> name.startsWith(fieldName + "."));
    }

    private static String rootName(String name) {
        int separator = name.indexOf('.');
        return name.substring(0, separator >= 0 ? separator : name.length());
    }

    private static Object safeValue(String name, Object value) {
        if (value == null) {
            return null;
        }
        if ("duration_ms".equals(name)) {
            return nonNegativeNumber(value);
        }
        if ("retry.attempt".equals(name)) {
            return boundedInteger(value, 1);
        }
        if ("retry.max_attempts".equals(name)) {
            return boundedInteger(value, 1);
        }
        if (SAFE_SIZE_FIELDS.contains(name)) {
            return boundedInteger(value, 0);
        }
        if ("event.outcome".equals(name)) {
            return allowedString(value, EVENT_OUTCOMES);
        }
        if ("disposition".equals(name)) {
            return allowedString(value, DISPOSITIONS);
        }
        if ("event.action".equals(name)) {
            return value instanceof String text && StringUtils.hasLength(text)
                    ? sanitizeText(text)
                    : null;
        }
        if ("url.full".equals(name) || "http.url".equals(name)) {
            return value instanceof String text && StringUtils.hasLength(text)
                    ? safeUrl(text)
                    : null;
        }
        if (value instanceof String text) {
            if (!StringUtils.hasLength(text)) {
                return null;
            }
            return sanitizeText(text);
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            return null;
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return null;
    }

    private static Double nonNegativeNumber(Object value) {
        double number;
        if (value instanceof Number numeric) {
            number = numeric.doubleValue();
        } else if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                number = Double.parseDouble(text);
            } catch (NumberFormatException failure) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(number) && number >= 0 ? number : null;
    }

    private static Long boundedInteger(Object value, long minimum) {
        BigInteger integer;
        try {
            if (value instanceof BigInteger bigInteger) {
                integer = bigInteger;
            } else if (value instanceof BigDecimal decimal) {
                integer = decimal.toBigIntegerExact();
            } else if (value instanceof Number number) {
                integer = new BigDecimal(number.toString()).toBigIntegerExact();
            } else if (value instanceof String text && StringUtils.hasText(text)) {
                integer = new BigInteger(text);
            } else {
                return null;
            }
            long result = integer.longValueExact();
            return result >= minimum ? result : null;
        } catch (ArithmeticException | NumberFormatException failure) {
            return null;
        }
    }

    private static String allowedString(Object value, Set<String> allowed) {
        return value instanceof String text && allowed.contains(text) ? text : null;
    }

    private static String safeUrl(String text) {
        try {
            URI uri = new URI(text);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return new URI(
                            uri.getScheme(),
                            null,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getRawPath(),
                            null,
                            null)
                    .toASCIIString();
        } catch (URISyntaxException failure) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void putNested(Map<String, Object> root, String name, Object value) {
        String[] parts = name.split("\\.");
        Map<String, Object> destination = root;
        for (int index = 0; index < parts.length - 1; index++) {
            Object existing = destination.get(parts[index]);
            if (existing == null) {
                Map<String, Object> nested = new LinkedHashMap<>();
                destination.put(parts[index], nested);
                destination = nested;
            } else if (existing instanceof Map<?, ?> map) {
                destination = (Map<String, Object>) map;
            } else {
                return;
            }
        }
        String leaf = parts[parts.length - 1];
        if (!destination.containsKey(leaf)) {
            destination.put(leaf, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void normalizeRetry(Map<String, Object> root) {
        Object value = root.get("retry");
        if (!(value instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> retry = (Map<String, Object>) value;
        Long attempt = (Long) retry.get("attempt");
        Long maximum = (Long) retry.get("max_attempts");
        if (maximum != null && (attempt == null || maximum < attempt)) {
            retry.remove("max_attempts");
        }
        if (retry.isEmpty()) {
            root.remove("retry");
        }
    }

    private static void addError(
            Map<String, Object> root, ILoggingEvent event, Map<String, String> mdc) {
        Map<String, Object> error = new LinkedHashMap<>();
        String code = errorCode(event.getKeyValuePairs(), mdc);
        IThrowableProxy throwable = event.getThrowableProxy();
        boolean finalError = event.getLevel().isGreaterOrEqual(Level.ERROR);
        if (code == null && finalError) {
            code = DEFAULT_ERROR_CODE;
        }
        if (code != null) {
            error.put("code", code);
        }
        if (throwable != null) {
            error.put("type", throwable.getClassName());
            if (finalError) {
                error.put("message", "Failure of type " + throwable.getClassName());
                error.put("stack_trace", safeStackTrace(throwable));
            }
        }
        if (!error.isEmpty()) {
            root.put("error", error);
        }
    }

    private static String errorCode(List<KeyValuePair> fields, Map<String, String> mdc) {
        if (fields != null) {
            for (KeyValuePair field : fields) {
                if ("error.code".equals(field.key)
                        && field.value instanceof String candidate
                        && ERROR_CODE.matcher(candidate).matches()) {
                    return candidate;
                }
            }
        }
        String candidate = mdc == null ? null : mdc.get("error.code");
        return candidate != null && ERROR_CODE.matcher(candidate).matches() ? candidate : null;
    }

    private static String safeStackTrace(IThrowableProxy throwable) {
        StringBuilder result = new StringBuilder();
        Set<IThrowableProxy> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        IThrowableProxy current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            if (depth > 0) {
                result.append("Caused by: ");
            }
            result.append(current.getClassName()).append('\n');
            StackTraceElementProxy[] frames = current.getStackTraceElementProxyArray();
            int frameCount = Math.min(frames.length, MAX_STACK_FRAMES_PER_CAUSE);
            for (int index = 0; index < frameCount; index++) {
                result.append('\t').append(frames[index].getSTEAsString()).append('\n');
            }
            if (frames.length > frameCount) {
                result.append("\t... ").append(frames.length - frameCount).append(" frames omitted\n");
            }
            current = current.getCause();
            depth++;
        }
        if (current != null) {
            result.append("Caused by: additional causes omitted\n");
        }
        return result.toString();
    }

    private static void addMarkers(Map<String, Object> root, List<Marker> markers) {
        if (markers == null || markers.isEmpty()) {
            return;
        }
        Collection<String> names = new ArrayList<>();
        for (Marker marker : markers) {
            names.add(marker.getName());
        }
        root.put(
                "tags",
                names.stream()
                        .map(ModuveraEcsStructuredLogFormatter::sanitizeText)
                        .filter(StringUtils::hasText)
                        .sorted()
                        .distinct()
                        .toList());
    }

    private static String sanitizeText(String text) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replace('\r', ' ').replace('\n', ' ');
        Matcher matcher = SENSITIVE_ASSIGNMENT.matcher(singleLine);
        String safe = matcher.replaceAll(result -> Matcher.quoteReplacement(
                isSensitiveName(result.group(1))
                        ? result.group(1) + "=[REDACTED]"
                        : result.group()));
        matcher = URL.matcher(safe);
        StringBuilder withoutQueries = new StringBuilder();
        while (matcher.find()) {
            String sanitizedUrl = safeUrl(matcher.group());
            matcher.appendReplacement(
                    withoutQueries,
                    Matcher.quoteReplacement(sanitizedUrl == null ? "[REDACTED_URL]" : sanitizedUrl));
        }
        matcher.appendTail(withoutQueries);
        safe = withoutQueries.toString();
        return safe.length() <= MAX_TEXT_LENGTH ? safe : safe.substring(0, MAX_TEXT_LENGTH);
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            String name = (String) entries[index];
            Object value = entries[index + 1];
            if (value instanceof String text && !StringUtils.hasLength(text)) {
                continue;
            }
            if (value != null && (!(value instanceof Map<?, ?> map) || !map.isEmpty())) {
                values.put(name, value);
            }
        }
        return values;
    }
}
