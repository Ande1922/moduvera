package io.github.ande1922.moduvera.security.autoconfigure;

import io.github.ande1922.moduvera.security.jwt.ModuveraJwtAuthenticationConverter;
import io.github.ande1922.moduvera.security.web.ExecutionContextFilter;
import io.github.ande1922.moduvera.security.web.SecurityProblemWriter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

public final class ModuveraResourceServerConfigurer {

    private final ModuveraJwtAuthenticationConverter jwtConverter;
    private final ExecutionContextFilter contextFilter;
    private final SecurityProblemWriter problemWriter;

    public ModuveraResourceServerConfigurer(
            ModuveraJwtAuthenticationConverter jwtConverter,
            ExecutionContextFilter contextFilter,
            SecurityProblemWriter problemWriter) {
        this.jwtConverter = jwtConverter;
        this.contextFilter = contextFilter;
        this.problemWriter = problemWriter;
    }

    public void configure(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health/**", "/actuator/info")
                .permitAll()
                .anyRequest()
                .authenticated());
        http.oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                .authenticationEntryPoint(problemWriter)
                .accessDeniedHandler(problemWriter));
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(problemWriter)
                .accessDeniedHandler(problemWriter));
        http.addFilterAfter(contextFilter, BearerTokenAuthenticationFilter.class);
    }
}
