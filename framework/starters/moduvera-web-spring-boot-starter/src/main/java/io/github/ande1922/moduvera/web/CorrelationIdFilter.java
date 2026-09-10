package io.github.ande1922.moduvera.web;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {
    public static final String HEADER = "X-Correlation-Id";
    public static final String REQUEST_ATTRIBUTE = "io.github.ande1922.moduvera.correlationId";
    private final boolean internal;

    /** Public ingress is the safe default. Internal mode requires protected assembly configuration. */
    public CorrelationIdFilter() {
        this(false);
    }

    public CorrelationIdFilter(boolean internal) {
        this.internal = internal;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var state = ServletRequestDiagnostics.establish(request, internal);
        var observedResponse = new DiagnosticResponse(response, state);
        state.attach(observedResponse);
        try {
            chain.doFilter(new DiagnosticRequest(request, observedResponse, state), observedResponse);
        } catch (IOException | ServletException | RuntimeException failure) {
            state.failed(failure);
            throw failure;
        }
        // The container listener owns completion, after error dispatch and async termination.
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static final class DiagnosticRequest extends HttpServletRequestWrapper {
        private final ServletRequestDiagnostics state;
        private final HttpServletResponse response;

        private DiagnosticRequest(HttpServletRequest request, HttpServletResponse response, ServletRequestDiagnostics state) {
            super(request);
            this.state = state;
            this.response = response;
        }

        @Override
        public AsyncContext startAsync() {
            return observe(super.startAsync(this, response));
        }

        @Override
        public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
            return observe(super.startAsync(request, response));
        }

        private AsyncContext observe(AsyncContext context) {
            if (state.beginAsyncObservation()) {
                context.addListener(new DiagnosticAsyncListener(state));
            }
            return context;
        }
    }

    private record DiagnosticAsyncListener(ServletRequestDiagnostics state) implements AsyncListener {
        @Override
        public void onComplete(AsyncEvent event) {
            state.complete();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            state.timedOut();
        }

        @Override
        public void onError(AsyncEvent event) {
            state.failed(event.getThrowable());
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
