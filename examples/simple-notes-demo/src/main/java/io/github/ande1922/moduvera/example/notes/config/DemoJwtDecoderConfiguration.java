package io.github.ande1922.moduvera.example.notes.config;

import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
class DemoJwtDecoderConfiguration {

    @Bean
    JwtDecoder demoJwtDecoder() {
        return token -> switch (token) {
            case "tenant-a-writer" -> user(token, "alice", "tenant-a", List.of("notes:read", "notes:write"));
            case "tenant-b-writer" -> user(token, "bob", "tenant-b", List.of("notes:read", "notes:write"));
            case "tenant-a-reader" -> user(token, "reader", "tenant-a", List.of("notes:read"));
            default -> throw new JwtException("unknown demo token");
        };
    }

    private static Jwt user(String token, String subject, String tenantId, List<String> permissions) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "demo")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("actor_type", "USER")
                .claim("tenant_id", tenantId)
                .claim("permissions", permissions)
                .build();
    }
}
