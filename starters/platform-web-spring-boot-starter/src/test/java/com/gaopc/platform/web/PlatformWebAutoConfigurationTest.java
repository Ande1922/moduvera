package com.gaopc.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaopc.platform.error.CodedException;
import com.gaopc.platform.error.ErrorCode;
import com.gaopc.platform.web.autoconfigure.PlatformWebAutoConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

class PlatformWebAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformWebAutoConfiguration.class));

    @Test
    void suppliesTheOpinionatedWebBoundaryBeans() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(CorrelationIdFilter.class)
                .hasSingleBean(ProblemStatusResolver.class)
                .hasSingleBean(ApiExceptionHandler.class));
    }

    @Test
    void composesProviderOwnedProblemStatusContributors() {
        runner.withUserConfiguration(ProviderMappings.class).run(context -> {
            ProblemStatusResolver resolver = context.getBean(ProblemStatusResolver.class);

            assertThat(resolver.statusFor(new TestCodedException("catalog.not-found")))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(resolver.statusFor(new TestCodedException("order.not-found")))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(resolver.statusFor(new TestCodedException("other.failure")))
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ProviderMappings {

        @Bean
        ProblemStatusContributor catalogProblemStatuses() {
            return exception -> exception.code().value().startsWith("catalog.")
                    ? Optional.of(HttpStatus.NOT_FOUND)
                    : Optional.empty();
        }

        @Bean
        ProblemStatusContributor orderProblemStatuses() {
            return exception -> exception.code().value().startsWith("order.")
                    ? Optional.of(HttpStatus.NOT_FOUND)
                    : Optional.empty();
        }
    }

    private static final class TestCodedException extends CodedException {

        TestCodedException(String code) {
            super(new ErrorCode(code), "test failure");
        }
    }
}
