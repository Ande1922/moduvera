package io.github.ande1922.moduvera.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;

/** Observes transport failures without buffering or counting response content. */
final class DiagnosticResponse extends HttpServletResponseWrapper {
    private final ServletRequestDiagnostics state;
    private ServletOutputStream output;

    DiagnosticResponse(HttpServletResponse response, ServletRequestDiagnostics state) {
        super(response);
        this.state = state;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (output == null) {
            output = new ObservedOutputStream(super.getOutputStream());
        }
        return output;
    }

    @Override
    public void flushBuffer() throws IOException {
        try {
            super.flushBuffer();
        } catch (IOException failure) {
            state.failed(failure);
            throw failure;
        }
    }

    @Override
    public void reset() {
        super.reset();
        state.attach(this);
    }

    private final class ObservedOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;

        private ObservedOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override public boolean isReady() {
            return delegate.isReady();
        }

        @Override public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(new WriteListener() {
                @Override public void onWritePossible() throws IOException {
                    listener.onWritePossible();
                }
                @Override public void onError(Throwable failure) {
                    state.failed(failure);
                    listener.onError(failure);
                }
            });
        }

        @Override public void write(int value) throws IOException {
            try {
                delegate.write(value);
            } catch (IOException failure) {
                state.failed(failure);
                throw failure;
            }
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            try {
                delegate.write(value, offset, length);
            } catch (IOException failure) {
                state.failed(failure);
                throw failure;
            }
        }

        @Override public void flush() throws IOException {
            try {
                delegate.flush();
            } catch (IOException failure) {
                state.failed(failure);
                throw failure;
            }
        }

        @Override public void close() throws IOException {
            try {
                delegate.close();
            } catch (IOException failure) {
                state.failed(failure);
                throw failure;
            }
        }
    }
}
