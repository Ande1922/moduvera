package io.github.ande1922.moduvera.web;

import io.github.ande1922.moduvera.logging.DiagnosticLogProjection;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/** Covers container final handlers after filter unwinding; never owns errors or completion. */
public final class TomcatRequestDiagnosticsValve extends ValveBase {
    public TomcatRequestDiagnosticsValve() {
        super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        try (var ignored = DiagnosticLogProjection.open(() -> {
            var diagnostics = ServletRequestDiagnostics.find(request);
            return diagnostics == null ? null : diagnostics.snapshot();
        })) {
            getNext().invoke(request, response);
        }
    }
}
