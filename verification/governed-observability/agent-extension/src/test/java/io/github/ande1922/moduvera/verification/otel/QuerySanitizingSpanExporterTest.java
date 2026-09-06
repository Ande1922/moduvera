package io.github.ande1922.moduvera.verification.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuerySanitizingSpanExporterTest {

    private static final AttributeKey<String> SAFE = AttributeKey.stringKey("safe");

    @Test
    void stripsEveryQueryAndFragmentWhilePreservingSpanIdentityAndBatching() {
        try (CapturingExporter delegate = new CapturingExporter();
                QuerySanitizingSpanExporter exporter = new QuerySanitizingSpanExporter(delegate)) {
            List<LinkData> links = List.of(LinkData.create(SpanContext.create(
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    "5555555555555555",
                    TraceFlags.getSampled(),
                    TraceState.getDefault())));
            StatusData status = StatusData.error();
            SpanData first = span(
                    "1111111111111111",
                    "2222222222222222",
                    links,
                    status,
                    Attributes.builder()
                            .put(QuerySanitizingSpanExporter.URL_FULL,
                                    "https://example.test/search?alpha=1&password=a%2Fb#private")
                            .put(QuerySanitizingSpanExporter.URL_QUERY, "alpha=1&password=a%2Fb")
                            .put(QuerySanitizingSpanExporter.HTTP_URL,
                                    "http://example.test/legacy?anything=%3F")
                            .put(SAFE, "retained")
                            .build());
            SpanData second = span(
                    "3333333333333333",
                    "4444444444444444",
                    List.of(),
                    StatusData.ok(),
                    Attributes.builder()
                            .put(QuerySanitizingSpanExporter.URL_FULL,
                                    "https://example.test/empty?#fragment")
                            .build());

            CompletableResultCode result = exporter.export(List.of(first, second));

            assertThat(result.isSuccess()).isTrue();
            assertThat(delegate.batches).hasSize(1);
            assertThat(delegate.batches.getFirst()).hasSize(2);
            SpanData sanitizedFirst = delegate.batches.getFirst().getFirst();
            assertThat(sanitizedFirst.getSpanId()).isEqualTo("1111111111111111");
            assertThat(sanitizedFirst.getParentSpanId()).isEqualTo("2222222222222222");
            assertThat(sanitizedFirst.getLinks()).isSameAs(links);
            assertThat(sanitizedFirst.getStatus().getStatusCode()).isEqualTo(status.getStatusCode());
            assertThat(sanitizedFirst.getStatus().getDescription()).isEmpty();
            assertThat(sanitizedFirst.getAttributes().get(QuerySanitizingSpanExporter.URL_FULL))
                    .isEqualTo("https://example.test/search");
            assertThat(sanitizedFirst.getAttributes().get(QuerySanitizingSpanExporter.HTTP_URL))
                    .isEqualTo("http://example.test/legacy");
            assertThat(sanitizedFirst.getAttributes().get(QuerySanitizingSpanExporter.URL_QUERY)).isNull();
            assertThat(sanitizedFirst.getAttributes().get(SAFE)).isEqualTo("retained");
            assertThat(delegate.batches.getFirst().get(1).getAttributes()
                            .get(QuerySanitizingSpanExporter.URL_FULL))
                    .isEqualTo("https://example.test/empty");
        }
    }

    @Test
    void dropsMalformedUrlAttributesInsteadOfExportingTheirOriginalValues() {
        Attributes sanitized = QuerySanitizingSpanExporter.sanitize(
                Attributes.builder()
                        .put(QuerySanitizingSpanExporter.URL_FULL, "http://[invalid?secret=value")
                        .put(QuerySanitizingSpanExporter.HTTP_URL, "https://example.test/%ZZ?secret=value")
                        .put(QuerySanitizingSpanExporter.URL_QUERY, "secret=value")
                        .put(SAFE, "retained")
                        .build());

        assertThat(sanitized.get(QuerySanitizingSpanExporter.URL_FULL)).isNull();
        assertThat(sanitized.get(QuerySanitizingSpanExporter.HTTP_URL)).isNull();
        assertThat(sanitized.get(QuerySanitizingSpanExporter.URL_QUERY)).isNull();
        assertThat(sanitized.get(SAFE)).isEqualTo("retained");
    }

    @Test
    void stripsUrlUserInfoWhilePreservingEncodedPaths() {
        Attributes sanitized = QuerySanitizingSpanExporter.sanitize(
                Attributes.builder()
                        .put(QuerySanitizingSpanExporter.URL_FULL,
                                "https://alice:p%40ss@example.test/path%2Fsegment?secret=value#fragment")
                        .build());

        assertThat(sanitized.get(QuerySanitizingSpanExporter.URL_FULL))
                .isEqualTo("https://example.test/path%2Fsegment");
    }

    @Test
    void stripsAutomaticExceptionTextCapturedHeadersAndStatusDescriptions() {
        AttributeKey<String> exceptionMessage = AttributeKey.stringKey("exception.message");
        AttributeKey<String> exceptionStacktrace = AttributeKey.stringKey("exception.stacktrace");
        AttributeKey<String> authorization = AttributeKey.stringKey("http.request.header.authorization");
        EventData exception = EventData.create(
                3L,
                "exception",
                Attributes.builder()
                        .put(exceptionMessage, "query=secret")
                        .put(exceptionStacktrace, "SELECT secret")
                        .put(authorization, "Bearer secret")
                        .put(SAFE, "retained")
                        .build());
        SpanData source = span(
                "1111111111111111",
                "2222222222222222",
                List.of(),
                StatusData.create(io.opentelemetry.api.trace.StatusCode.ERROR, "query=secret"),
                Attributes.empty(),
                List.of(exception));

        try (CapturingExporter delegate = new CapturingExporter();
                QuerySanitizingSpanExporter exporter = new QuerySanitizingSpanExporter(delegate)) {
            exporter.export(List.of(source));

            SpanData sanitized = delegate.batches.getFirst().getFirst();
            assertThat(sanitized.getStatus().getStatusCode())
                    .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
            assertThat(sanitized.getStatus().getDescription()).isEmpty();
            assertThat(sanitized.getEvents()).hasSize(1);
            Attributes eventAttributes = sanitized.getEvents().getFirst().getAttributes();
            assertThat(eventAttributes.get(exceptionMessage)).isNull();
            assertThat(eventAttributes.get(exceptionStacktrace)).isNull();
            assertThat(eventAttributes.get(authorization)).isNull();
            assertThat(eventAttributes.get(SAFE)).isEqualTo("retained");
        }
    }

    private static SpanData span(
            String spanId,
            String parentSpanId,
            List<LinkData> links,
            StatusData status,
            Attributes attributes) {
        return span(spanId, parentSpanId, links, status, attributes, List.of());
    }

    private static SpanData span(
            String spanId,
            String parentSpanId,
            List<LinkData> links,
            StatusData status,
            Attributes attributes,
            List<EventData> events) {
        SpanContext spanContext = SpanContext.create(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                spanId,
                TraceFlags.getSampled(),
                TraceState.getDefault());
        SpanContext parentSpanContext = SpanContext.create(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                parentSpanId,
                TraceFlags.getSampled(),
                TraceState.getDefault());
        return new TestSpanData(spanContext, parentSpanContext, links, status, attributes, events);
    }

    private record TestSpanData(
            SpanContext spanContext,
            SpanContext parentSpanContext,
            List<LinkData> links,
            StatusData status,
            Attributes attributes,
            List<EventData> events)
            implements SpanData {

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public SpanKind getKind() {
            return SpanKind.INTERNAL;
        }

        @Override
        public SpanContext getSpanContext() {
            return spanContext;
        }

        @Override
        public SpanContext getParentSpanContext() {
            return parentSpanContext;
        }

        @Override
        public StatusData getStatus() {
            return status;
        }

        @Override
        public long getStartEpochNanos() {
            return 1L;
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        public List<EventData> getEvents() {
            return events;
        }

        @Override
        public List<LinkData> getLinks() {
            return links;
        }

        @Override
        public long getEndEpochNanos() {
            return 2L;
        }

        @Override
        public boolean hasEnded() {
            return true;
        }

        @Override
        public int getTotalRecordedEvents() {
            return events.size();
        }

        @Override
        public int getTotalRecordedLinks() {
            return links.size();
        }

        @Override
        public int getTotalAttributeCount() {
            return attributes.size();
        }

        @Override
        @SuppressWarnings("deprecation")
        public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
            return InstrumentationLibraryInfo.empty();
        }

        @Override
        public Resource getResource() {
            return Resource.empty();
        }
    }

    private static final class CapturingExporter implements SpanExporter {

        private final List<List<SpanData>> batches = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            batches.add(List.copyOf(spans));
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
