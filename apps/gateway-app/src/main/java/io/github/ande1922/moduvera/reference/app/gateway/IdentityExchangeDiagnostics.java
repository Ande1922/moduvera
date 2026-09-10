package io.github.ande1922.moduvera.reference.app.gateway;

import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import reactor.core.publisher.SignalType;

/** One observable WebClient exchange, including body processing and the configured deadline. */
final class IdentityExchangeDiagnostics {

    private static final Logger LOG = LoggerFactory.getLogger("http.client");
    private final long started = System.nanoTime();
    private final DiagnosticLogSnapshot snapshot;
    private volatile String method;
    private volatile String path;
    private volatile long requestBytes = -1;
    private volatile Integer status;
    private volatile long responseBytes = -1;
    private volatile Throwable failure;

    IdentityExchangeDiagnostics(String correlationId) {
        snapshot = DiagnosticLogSnapshot.captureWithoutIdentity(correlationId);
    }

    void request(ClientRequest request) {
        method = request.method().name();
        path = request.url().getRawPath();
        try {
            requestBytes = request.headers().getContentLength();
        } catch (NumberFormatException ignored) {
            requestBytes = -1;
        }
    }

    void response(ClientResponse response) {
        status = response.statusCode().value();
        try {
            responseBytes = response.headers().contentLength().orElse(-1);
        } catch (NumberFormatException ignored) {
            responseBytes = -1;
        }
    }

    void failed(Throwable failure) {
        this.failure = failure;
    }

    void complete(SignalType signal) {
        if (path == null) {
            return;
        }
        String outcome = failure != null ? "failure"
                : signal == SignalType.CANCEL || status == null || status < 200 ? "unknown"
                        : status < 400 ? "success" : "failure";
        try (var absent = LoggingContextSnapshot.absent().openScope(); var diagnostic = snapshot.openScope()) {
            var event = LOG.atInfo().addKeyValue("target", "identity-service")
                    .addKeyValue("http.request.method", method)
                    .addKeyValue("url.path", path)
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
