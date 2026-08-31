package io.github.ande1922.moduvera.reference.app.identity;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("identity")
public class IdentityProperties {

    private String issuer = "http://localhost:8081";
    private Duration sessionTtl = Duration.ofHours(8);
    private Duration jwtTtl = Duration.ofMinutes(5);
    private Set<String> allowedAudiences = new LinkedHashSet<>(Set.of("catalog-service", "order-service"));

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getJwtTtl() {
        return jwtTtl;
    }

    public void setJwtTtl(Duration jwtTtl) {
        this.jwtTtl = jwtTtl;
    }

    public Set<String> getAllowedAudiences() {
        return allowedAudiences;
    }

    public void setAllowedAudiences(Set<String> allowedAudiences) {
        this.allowedAudiences = new LinkedHashSet<>(allowedAudiences);
    }
}
