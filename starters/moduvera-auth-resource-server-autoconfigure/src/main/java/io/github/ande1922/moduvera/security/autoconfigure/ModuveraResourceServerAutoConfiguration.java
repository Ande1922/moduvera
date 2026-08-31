package io.github.ande1922.moduvera.security.autoconfigure;

import io.github.ande1922.moduvera.security.jwt.JwtExecutionContextFactory;
import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationConverter;
import io.github.ande1922.moduvera.security.web.DefaultRequestCorrelationIdResolver;
import io.github.ande1922.moduvera.security.web.ExecutionContextFilter;
import io.github.ande1922.moduvera.security.web.RequestCorrelationIdResolver;
import io.github.ande1922.moduvera.security.web.SecurityProblemWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.security.autoconfigure.web.servlet.ConditionalOnDefaultWebSecurity;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration(
        before = OAuth2ResourceServerWebSecurityAutoConfiguration.class,
        after = OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({JwtDecoder.class, HttpSecurity.class})
public class ModuveraResourceServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtExecutionContextFactory moduveraJwtExecutionContextFactory() {
        return new JwtExecutionContextFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    ModuveraJwtAuthenticationConverter moduveraJwtAuthenticationConverter(
            JwtExecutionContextFactory contextFactory) {
        return new ModuveraJwtAuthenticationConverter(contextFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    RequestCorrelationIdResolver moduveraRequestCorrelationIdResolver() {
        return new DefaultRequestCorrelationIdResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    ExecutionContextFilter moduveraExecutionContextFilter(RequestCorrelationIdResolver correlationIds) {
        return new ExecutionContextFilter(correlationIds);
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityProblemWriter moduveraSecurityProblemWriter(
            ObjectMapper objectMapper, RequestCorrelationIdResolver correlationIds) {
        return new SecurityProblemWriter(objectMapper, correlationIds);
    }

    @Bean
    @ConditionalOnMissingBean
    ModuveraResourceServerConfigurer moduveraResourceServerConfigurer(
            ModuveraJwtAuthenticationConverter jwtConverter,
            ExecutionContextFilter contextFilter,
            SecurityProblemWriter problemWriter) {
        return new ModuveraResourceServerConfigurer(jwtConverter, contextFilter, problemWriter);
    }

    @Bean
    @ConditionalOnBean(JwtDecoder.class)
    @ConditionalOnMissingBean
    ResourceServerConfigurationGuard moduveraResourceServerConfigurationGuard(
            OAuth2ResourceServerProperties properties) {
        return new ResourceServerConfigurationGuard(properties);
    }

    @Bean
    @ConditionalOnBean(JwtDecoder.class)
    @ConditionalOnDefaultWebSecurity
    SecurityFilterChain moduveraSecurityFilterChain(
            HttpSecurity http, ModuveraResourceServerConfigurer configurer) throws Exception {
        configurer.configure(http);
        return http.build();
    }
}
