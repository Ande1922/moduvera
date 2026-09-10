package io.github.ande1922.moduvera.web;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One diagnostic lifecycle per container request, including error and async dispatches. */
public final class ServletRequestDiagnostics {
    private static final String ATTRIBUTE = ServletRequestDiagnostics.class.getName();
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Logger CANONICAL = LoggerFactory.getLogger("http.request");
    private static final Logger LOG = LoggerFactory.getLogger(ServletRequestDiagnostics.class);
    private final long started = System.nanoTime();
    private final String correlationId;
    private final String method;
    private final String path;
    private final long requestBytes;
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean observingAsync = new AtomicBoolean();
    private volatile DiagnosticLogSnapshot snapshot;
    private volatile HttpServletResponse response;
    private volatile boolean transportFailure;
    private volatile Throwable unhandledFailure;
    private volatile boolean timeout;

    private ServletRequestDiagnostics(HttpServletRequest request, boolean internal) {
        String candidate = internal ? request.getHeader(CorrelationIdFilter.HEADER) : null;
        boolean accepted = candidate != null && SAFE_VALUE.matcher(candidate).matches();
        correlationId = accepted ? candidate : UUID.randomUUID().toString();
        method = request.getMethod();
        path = request.getRequestURI();
        requestBytes = request.getContentLengthLong();
        snapshot = DiagnosticLogSnapshot.captureWithoutIdentity(correlationId);
        if (internal && !accepted) {
            try (var ignored = snapshot.openScope()) {
                LOG.atWarn().log("内部请求关联缺失或非法，已补建关联");
            }
        }
    }

    public static ServletRequestDiagnostics establish(HttpServletRequest request, boolean internal) {
        synchronized (request) {
            ServletRequestDiagnostics existing = find(request);
            if (existing != null) {
                return existing;
            }
            ServletRequestDiagnostics created = new ServletRequestDiagnostics(request, internal);
            request.setAttribute(ATTRIBUTE, created);
            request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, created.correlationId);
            return created;
        }
    }

    public static ServletRequestDiagnostics find(ServletRequest request) {
        Object state = request.getAttribute(ATTRIBUTE);
        return state instanceof ServletRequestDiagnostics diagnostics ? diagnostics : null;
    }

    public String correlationId() {
        return correlationId;
    }

    public void authenticated(ExecutionContext trusted) {
        snapshot = snapshot.withIdentity(trusted);
    }

    public void authenticated(Actor actor, Initiator initiator, Optional<TenantId> tenant) {
        snapshot = snapshot.withIdentity(actor, initiator, tenant);
    }

    boolean beginAsyncObservation() {
        return observingAsync.compareAndSet(false, true);
    }

    void attach(HttpServletResponse value) {
        response = value;
        if (!value.isCommitted()) {
            value.setHeader(CorrelationIdFilter.HEADER, correlationId);
            if (snapshot.traceId() != null) {
                value.setHeader("X-Trace-Id", snapshot.traceId());
            }
        }
    }

    void failed(Throwable failure) {
        unhandledFailure = failure;
        Throwable cause = failure;
        while (cause != null && !(cause instanceof java.io.IOException)) {
            cause = cause.getCause();
        }
        if (cause != null) {
            transportFailure = true;
        }
    }

    void timedOut() {
        timeout = true;
    }

    void complete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        HttpServletResponse terminalResponse = response;
        int status = terminalResponse == null ? 0 : terminalResponse.getStatus();
        String outcome = transportFailure || unhandledFailure != null || status >= 400 || (timeout && status < 200) ? "failure"
                : status >= 200 ? "success" : "unknown";
        try (var ignored = snapshot.openScope()) {
            var event = CANONICAL.atInfo()
                    .addKeyValue("event.outcome", outcome)
                    .addKeyValue("http.request.method", method)
                    .addKeyValue("url.path", path)
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0);
            if (status >= 200 && !transportFailure) {
                event.addKeyValue("http.response.status_code", status);
            }
            if (requestBytes >= 0) {
                event.addKeyValue("http.request.body.bytes", requestBytes);
            }
            if (terminalResponse != null && !transportFailure) {
                String length = terminalResponse.getHeader("Content-Length");
                if (length != null) {
                    event.addKeyValue("http.response.body.bytes", length);
                }
            }
            event.log("HTTP 请求结束");

        }
    }
}
