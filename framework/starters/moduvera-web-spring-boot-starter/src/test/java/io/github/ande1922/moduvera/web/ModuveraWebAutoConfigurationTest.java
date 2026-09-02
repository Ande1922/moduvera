package io.github.ande1922.moduvera.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ande1922.moduvera.authorization.PermissionCode;
import io.github.ande1922.moduvera.authorization.PermissionDeniedException;
import io.github.ande1922.moduvera.error.CodedException;
import io.github.ande1922.moduvera.error.ErrorCode;
import io.github.ande1922.moduvera.web.autoconfigure.ModuveraWebAutoConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ModuveraWebAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModuveraWebAutoConfiguration.class));

    @Test
    void suppliesTheOpinionatedWebBoundaryBeans() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(CorrelationIdFilter.class)
                .hasSingleBean(ProblemStatusResolver.class)
                .hasSingleBean(ApiExceptionHandler.class));
    }

    @Test
    void rendersPermissionDenialAsForbiddenProblemDetails() {
        runner.run(context -> {
            var mockMvc = MockMvcBuilders.standaloneSetup(new PermissionDeniedController())
                    .setControllerAdvice(context.getBean(ApiExceptionHandler.class))
                    .addFilters(context.getBean(CorrelationIdFilter.class))
                    .build();

            mockMvc.perform(get("/permission-denied")
                            .header(CorrelationIdFilter.HEADER, "corr-permission-42"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-permission-42"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:problem:security.permission-denied"))
                    .andExpect(jsonPath("$.title").value("Request could not be completed"))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.detail").value("Required permission is missing: catalog:read"))
                    .andExpect(jsonPath("$.code").value("security.permission-denied"))
                    .andExpect(jsonPath("$.correlationId").value("corr-permission-42"));
        });
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

    @Test
    void givesProviderOwnedStatusContributorsPrecedence() {
        runner.withUserConfiguration(ProviderMappings.class).run(context -> {
            var mockMvc = MockMvcBuilders.standaloneSetup(new ProviderPermissionStatusController())
                    .setControllerAdvice(context.getBean(ApiExceptionHandler.class))
                    .addFilters(context.getBean(CorrelationIdFilter.class))
                    .build();

            mockMvc.perform(get("/provider-permission-status")
                            .header(CorrelationIdFilter.HEADER, "corr-provider-42"))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("security.permission-denied"))
                    .andExpect(jsonPath("$.correlationId").value("corr-provider-42"));
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

        @Bean
        ProblemStatusContributor providerPermissionStatuses() {
            return exception -> exception instanceof PermissionDeniedException
                    ? Optional.of(HttpStatus.CONFLICT)
                    : Optional.empty();
        }
    }

    private static final class TestCodedException extends CodedException {

        TestCodedException(String code) {
            super(new ErrorCode(code), "test failure");
        }
    }

    @RestController
    private static final class PermissionDeniedController {

        @GetMapping("/permission-denied")
        String permissionDenied() {
            throw new PermissionDeniedException(new PermissionCode("catalog:read"));
        }
    }

    @RestController
    private static final class ProviderPermissionStatusController {

        @GetMapping("/provider-permission-status")
        String providerStatus() {
            throw new PermissionDeniedException(new PermissionCode("catalog:read"));
        }
    }
}
