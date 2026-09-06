package io.github.ande1922.moduvera.verification.otel;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/** Installs export-time privacy enforcement into the SDK owned by the Java Agent. */
public final class GovernedAgentExtension implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addSpanExporterCustomizer(
                (delegate, ignoredConfiguration) -> new QuerySanitizingSpanExporter(delegate));
        System.setProperty(GovernedAgentRuntimeGuard.ACTIVE_PROPERTY, Boolean.TRUE.toString());
        System.err.println("Governed OpenTelemetry Agent extension: ACTIVE");
    }
}
