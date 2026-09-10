package io.github.ande1922.moduvera.web.autoconfigure;

import io.github.ande1922.moduvera.authorization.PermissionDeniedException;
import io.github.ande1922.moduvera.web.ApiExceptionHandler;
import io.github.ande1922.moduvera.web.CorrelationIdFilter;
import io.github.ande1922.moduvera.web.RequestDiagnosticsListener;
import org.springframework.core.env.Environment;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import jakarta.servlet.DispatcherType;
import io.github.ande1922.moduvera.web.ProblemStatusContributor;
import io.github.ande1922.moduvera.web.ProblemStatusResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;

@AutoConfiguration
public class ModuveraWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProblemStatusResolver moduveraProblemStatusResolver(
            ObjectProvider<ProblemStatusContributor> contributors) {
        return exception -> contributors.orderedStream()
                .map(contributor -> contributor.statusFor(exception))
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseGet(() -> exception instanceof PermissionDeniedException
                        ? HttpStatus.FORBIDDEN
                        : HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter moduveraCorrelationIdFilter(Environment environment) {
        return new CorrelationIdFilter(environment.getProperty("moduvera.web.internal-ingress", Boolean.class, false));
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> moduveraRequestDiagnosticsRegistration(CorrelationIdFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        registration.setOrder(filter.getOrder());
        return registration;
    }

    @Bean
    ServletListenerRegistrationBean<RequestDiagnosticsListener> moduveraRequestDiagnosticsListener() {
        return new ServletListenerRegistrationBean<>(new RequestDiagnosticsListener());
    }

    @Bean
    @ConditionalOnMissingBean
    ApiExceptionHandler moduveraApiExceptionHandler(ProblemStatusResolver statusResolver) {
        return new ApiExceptionHandler(statusResolver);
    }
}
