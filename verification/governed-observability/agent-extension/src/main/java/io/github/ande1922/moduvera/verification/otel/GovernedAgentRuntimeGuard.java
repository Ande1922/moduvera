package io.github.ande1922.moduvera.verification.otel;

import java.lang.instrument.Instrumentation;

/** Aborts before application main unless the official Agent loaded the governed extension SPI. */
public final class GovernedAgentRuntimeGuard {

    static final String ACTIVE_PROPERTY = "io.github.ande1922.moduvera.otel.extension.active";

    private GovernedAgentRuntimeGuard() {}

    public static void premain(String ignoredArguments, Instrumentation ignoredInstrumentation) {
        if (!Boolean.parseBoolean(System.getProperty(ACTIVE_PROPERTY))) {
            System.err.println("Governed OpenTelemetry Agent extension handshake: FAILED");
            throw new IllegalStateException("governed OpenTelemetry Agent extension was not loaded");
        }
        System.err.println("Governed OpenTelemetry Agent extension handshake: PASS");
    }
}
