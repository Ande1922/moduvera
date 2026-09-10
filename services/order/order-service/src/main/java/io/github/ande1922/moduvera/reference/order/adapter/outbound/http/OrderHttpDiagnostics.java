package io.github.ande1922.moduvera.reference.order.adapter.outbound.http;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

/** Observes the named HC5 groups below token caching without introducing request buffering. */
final class OrderHttpDiagnostics implements ClientHttpRequestFactory {

    private static final Logger LOG = LoggerFactory.getLogger("http.client");
    private final ClientHttpRequestFactory delegate;
    private final String target;

    OrderHttpDiagnostics(ClientHttpRequestFactory delegate, String target) {
        this.delegate = delegate;
        this.target = target;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        return new Request(delegate.createRequest(uri, method));
    }

    private final class Request extends HttpRequestWrapper implements ClientHttpRequest, StreamingHttpOutputMessage {
        private final ClientHttpRequest request;

        private Request(ClientHttpRequest request) {
            super(request);
            this.request = request;
        }

        @Override
        public void setBody(Body body) {
            // The named groups require HC5, whose request supports this streaming contract.
            ((StreamingHttpOutputMessage) request).setBody(body);
        }

        @Override
        public OutputStream getBody() throws IOException {
            return request.getBody();
        }

        @Override
        public ClientHttpResponse execute() throws IOException {
            var context = ExecutionContextHolder.require();
            getHeaders().set("X-Correlation-Id", context.correlationId());
            Attempt attempt = new Attempt(request, target, DiagnosticLogSnapshot.capture(context.correlationId()));
            try {
                ClientHttpResponse response = request.execute();
                try {
                    attempt.status = response.getStatusCode().value();
                    attempt.responseBytes = knownLength(response.getHeaders());
                    return new Response(response, attempt);
                } catch (IOException | RuntimeException failure) {
                    response.close();
                    throw failure;
                }
            } catch (IOException | RuntimeException failure) {
                attempt.failed(failure);
                attempt.complete();
                throw failure;
            }
        }
    }

    private static final class Response implements ClientHttpResponse {
        private final ClientHttpResponse response;
        private final Attempt attempt;

        private Response(ClientHttpResponse response, Attempt attempt) {
            this.response = response;
            this.attempt = attempt;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return response.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            try {
                return new FilterInputStream(response.getBody()) {
                    @Override
                    public int read() throws IOException {
                        try {
                            return in.read();
                        } catch (IOException failure) {
                            attempt.failed(failure);
                            throw failure;
                        }
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        try {
                            return in.read(buffer, offset, length);
                        } catch (IOException failure) {
                            attempt.failed(failure);
                            throw failure;
                        }
                    }
                };
            } catch (IOException failure) {
                attempt.failed(failure);
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                response.close();
            } catch (RuntimeException failure) {
                attempt.failed(failure);
                throw failure;
            } finally {
                attempt.complete();
            }
        }
    }

    private static final class Attempt {
        private final long started = System.nanoTime();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final String method;
        private final String path;
        private final String target;
        private final long requestBytes;
        private final DiagnosticLogSnapshot snapshot;
        private Integer status;
        private long responseBytes = -1;
        private Throwable failure;

        private Attempt(ClientHttpRequest request, String target, DiagnosticLogSnapshot snapshot) {
            this.method = request.getMethod().name();
            this.path = request.getURI().getRawPath();
            this.target = target;
            this.requestBytes = knownLength(request.getHeaders());
            this.snapshot = snapshot;
        }

        private void failed(Throwable failure) {
            this.failure = failure;
        }

        private void complete() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            String outcome = failure != null ? "failure"
                    : status == null || status < 200 ? "unknown" : status < 400 ? "success" : "failure";
            try (var absent = LoggingContextSnapshot.absent().openScope(); var diagnostic = snapshot.openScope()) {
                var event = LOG.atInfo().addKeyValue("target", target)
                        .addKeyValue("http.request.method", method).addKeyValue("url.path", path)
                        .addKeyValue("event.outcome", outcome)
                        .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0);
                if (status != null) {
                    event.addKeyValue("http.response.status_code", status);
                }
                if (requestBytes >= 0) {
                    event.addKeyValue("http.request.body.bytes", requestBytes);
                }
                if (responseBytes >= 0) {
                    event.addKeyValue("http.response.body.bytes", responseBytes);
                }
                if (failure != null) {
                    event.setCause(failure);
                }
                event.log("HTTP出站调用结束");
            }
        }
    }

    private static long knownLength(HttpHeaders headers) {
        try {
            return headers.getContentLength();
        } catch (NumberFormatException ignored) {
            // Diagnostic metadata must not introduce a new response-validation failure.
            return -1;
        }
    }
}
