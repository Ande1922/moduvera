package io.github.ande1922.moduvera.web.autoconfigure;

import io.github.ande1922.moduvera.web.ApiExceptionHandler;
import io.github.ande1922.moduvera.web.CorrelationIdFilter;
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
                .orElse(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter moduveraCorrelationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    ApiExceptionHandler moduveraApiExceptionHandler(ProblemStatusResolver statusResolver) {
        return new ApiExceptionHandler(statusResolver);
    }
}
