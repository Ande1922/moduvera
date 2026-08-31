package com.gaopc.platform.web.autoconfigure;

import com.gaopc.platform.web.ApiExceptionHandler;
import com.gaopc.platform.web.CorrelationIdFilter;
import com.gaopc.platform.web.ProblemStatusContributor;
import com.gaopc.platform.web.ProblemStatusResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;

@AutoConfiguration
public class PlatformWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProblemStatusResolver platformProblemStatusResolver(
            ObjectProvider<ProblemStatusContributor> contributors) {
        return exception -> contributors.orderedStream()
                .map(contributor -> contributor.statusFor(exception))
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElse(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter platformCorrelationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    ApiExceptionHandler platformApiExceptionHandler(ProblemStatusResolver statusResolver) {
        return new ApiExceptionHandler(statusResolver);
    }
}
