package io.github.ande1922.moduvera.security.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.security.jwt.JwtExecutionContextFactory;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationConverter;
import io.github.ande1922.moduvera.security.web.ExecutionContextFilter;
import io.github.ande1922.moduvera.security.web.RequestCorrelationIdResolver;
import io.github.ande1922.moduvera.security.web.SecurityProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class ModuveraResourceServerAutoConfigurationTest {

    @Test
    void suppliesTheResourceServerBoundaryWithoutForcingAJwtDecoder() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModuveraResourceServerAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(JwtExecutionContextFactory.class)
                            .hasSingleBean(ModuveraJwtAuthenticationConverter.class)
                            .hasSingleBean(RequestCorrelationIdResolver.class)
                            .hasSingleBean(ExecutionContextFilter.class)
                            .hasSingleBean(SecurityProblemWriter.class)
                            .hasSingleBean(ModuveraResourceServerConfigurer.class);
                    assertThat(context).doesNotHaveBean(ResourceServerConfigurationGuard.class);
                });
    }
}
