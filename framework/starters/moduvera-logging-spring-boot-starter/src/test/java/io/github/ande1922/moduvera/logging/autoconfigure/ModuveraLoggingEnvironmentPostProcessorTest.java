package io.github.ande1922.moduvera.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ModuveraLoggingEnvironmentPostProcessorTest {

    private final ModuveraLoggingEnvironmentPostProcessor processor =
            new ModuveraLoggingEnvironmentPostProcessor();

    @Test
    void selectsGovernedFormatterWhenConsoleFormatIsUnspecified() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty(
                        ModuveraLoggingEnvironmentPostProcessor.CONSOLE_FORMAT_PROPERTY))
                .isEqualTo(ModuveraEcsStructuredLogFormatter.class.getName());
        assertThat(environment.getPropertySources().get(
                        ModuveraLoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isNotNull();
    }

    @Test
    void replacesNativeEcsBecauseItDropsCodeAndCauseEvents() {
        StandardEnvironment environment = environmentWithConsoleFormat("ecs");

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty(
                        ModuveraLoggingEnvironmentPostProcessor.CONSOLE_FORMAT_PROPERTY))
                .isEqualTo(ModuveraEcsStructuredLogFormatter.class.getName());
    }

    @Test
    void leavesAnExplicitAlternativeFormatterUntouched() {
        StandardEnvironment environment = environmentWithConsoleFormat("example.CustomFormatter");

        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertThat(environment.getProperty(
                        ModuveraLoggingEnvironmentPostProcessor.CONSOLE_FORMAT_PROPERTY))
                .isEqualTo("example.CustomFormatter");
        assertThat(environment.getPropertySources().get(
                        ModuveraLoggingEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isNull();
    }

    private static StandardEnvironment environmentWithConsoleFormat(String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource(
                        "fixture",
                        java.util.Map.of(
                                ModuveraLoggingEnvironmentPostProcessor.CONSOLE_FORMAT_PROPERTY,
                                value)));
        return environment;
    }
}
