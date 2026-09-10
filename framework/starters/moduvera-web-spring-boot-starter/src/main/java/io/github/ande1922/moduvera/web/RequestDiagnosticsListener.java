package io.github.ande1922.moduvera.web;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;

/** Container completion includes synchronous error dispatches and asynchronous response closure. */
public final class RequestDiagnosticsListener implements ServletRequestListener {
    @Override
    public void requestDestroyed(ServletRequestEvent event) {
        var state = ServletRequestDiagnostics.find(event.getServletRequest());
        if (state != null) {
            state.complete();
        }
    }
}
