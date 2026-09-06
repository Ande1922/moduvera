package io.github.ande1922.moduvera.verification.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Removes query material immediately before spans cross the exporter boundary. */
final class QuerySanitizingSpanExporter implements SpanExporter {

    static final AttributeKey<String> HTTP_URL = AttributeKey.stringKey("http.url");
    static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");
    static final AttributeKey<String> URL_QUERY = AttributeKey.stringKey("url.query");
    private static final List<String> SENSITIVE_ATTRIBUTE_NAMES = List.of(
            "db.statement",
            "db.query.text",
            "error.message",
            "exception.message",
            "exception.stacktrace",
            "url.query");

    private final SpanExporter delegate;

    QuerySanitizingSpanExporter(SpanExporter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        List<SpanData> sanitized = spans.stream().map(SanitizedSpanData::new).map(SpanData.class::cast).toList();
        return delegate.export(sanitized);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    static Attributes sanitize(Attributes attributes) {
        AttributesBuilder sanitized = attributes.toBuilder().removeIf(QuerySanitizingSpanExporter::isSensitive);
        sanitizeUrl(attributes, sanitized, URL_FULL);
        sanitizeUrl(attributes, sanitized, HTTP_URL);
        return sanitized.build();
    }

    private static boolean isSensitive(AttributeKey<?> key) {
        String name = key.getKey();
        return SENSITIVE_ATTRIBUTE_NAMES.contains(name)
                || name.contains(".request.header.")
                || name.contains(".response.header.")
                || name.contains(".request.metadata.")
                || name.contains(".response.metadata.")
                || name.startsWith("messaging.header.");
    }

    private static void sanitizeUrl(
            Attributes source, AttributesBuilder destination, AttributeKey<String> key) {
        String value = source.get(key);
        if (value == null) {
            return;
        }
        try {
            URI parsed = URI.create(value);
            int fragment = value.indexOf('#');
            int query = value.indexOf('?');
            int end = value.length();
            if (fragment >= 0) {
                end = fragment;
            }
            if (query >= 0 && query < end) {
                end = query;
            }
            String withoutQuery = value.substring(0, end);
            if (parsed.getRawUserInfo() != null) {
                int authorityStart = withoutQuery.indexOf("//");
                int authorityEnd = withoutQuery.indexOf('/', authorityStart + 2);
                if (authorityEnd < 0) {
                    authorityEnd = withoutQuery.length();
                }
                int userInfoEnd = withoutQuery.lastIndexOf('@', authorityEnd);
                if (authorityStart < 0 || userInfoEnd < authorityStart + 2) {
                    destination.remove(key);
                    return;
                }
                withoutQuery = withoutQuery.substring(0, authorityStart + 2)
                        + withoutQuery.substring(userInfoEnd + 1);
            }
            destination.put(key, withoutQuery);
        } catch (IllegalArgumentException invalidUrl) {
            destination.remove(key);
        }
    }

    private static final class SanitizedSpanData extends DelegatingSpanData {

        private final Attributes sanitizedAttributes;
        private final List<EventData> sanitizedEvents;
        private final StatusData sanitizedStatus;

        private SanitizedSpanData(SpanData delegate) {
            super(delegate);
            sanitizedAttributes = sanitize(delegate.getAttributes());
            sanitizedEvents = delegate.getEvents().stream()
                    .map(event -> EventData.create(
                            event.getEpochNanos(),
                            event.getName(),
                            sanitize(event.getAttributes()),
                            event.getTotalAttributeCount()))
                    .toList();
            sanitizedStatus = switch (delegate.getStatus().getStatusCode()) {
                case UNSET -> StatusData.unset();
                case OK -> StatusData.ok();
                case ERROR -> StatusData.error();
            };
        }

        @Override
        public Attributes getAttributes() {
            return sanitizedAttributes;
        }

        @Override
        public List<EventData> getEvents() {
            return sanitizedEvents;
        }

        @Override
        public StatusData getStatus() {
            return sanitizedStatus;
        }
    }
}
