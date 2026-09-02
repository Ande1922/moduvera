package io.github.ande1922.moduvera.reference.app.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfiguration {

    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder identityPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    IdentitySigningKeys identitySigningKeys() {
        return new IdentitySigningKeys();
    }

    @Bean
    JwtEncoder identityJwtEncoder(IdentitySigningKeys keys) {
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(keys.privateJwk()));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    IdentityRepository identityRepository(JdbcTemplate jdbc) {
        return new IdentityRepository(jdbc);
    }

    @Bean
    IdentityService identityService(
            IdentityRepository identities,
            PasswordEncoder passwords,
            JwtEncoder jwt,
            IdentityProperties properties,
            Clock clock) {
        return new IdentityService(identities, passwords, jwt, properties, clock);
    }

    @Bean
    SecurityFilterChain identitySecurity(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/v1/session/login",
                                "/internal/api/v1/token/exchange",
                                "/internal/api/v1/service-token",
                                "/oauth2/jwks",
                                "/actuator/health")
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        return http.build();
    }
}
