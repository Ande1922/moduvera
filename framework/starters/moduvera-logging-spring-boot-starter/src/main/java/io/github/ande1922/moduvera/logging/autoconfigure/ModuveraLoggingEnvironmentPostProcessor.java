package io.github.ande1922.moduvera.logging.autoconfigure;

import io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Selects the governed formatter before Spring Boot initializes its logging system. */
public final class ModuveraLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String CONSOLE_FORMAT_PROPERTY = "logging.structured.format.console";
    static final String PROPERTY_SOURCE_NAME = "moduveraLoggingDefaults";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(CONSOLE_FORMAT_PROPERTY);
        if (configured == null || "ecs".equalsIgnoreCase(configured)) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(
                            PROPERTY_SOURCE_NAME,
                            Map.of(
                                    CONSOLE_FORMAT_PROPERTY,
                                    ModuveraEcsStructuredLogFormatter.class.getName())));
        }
    }
}
