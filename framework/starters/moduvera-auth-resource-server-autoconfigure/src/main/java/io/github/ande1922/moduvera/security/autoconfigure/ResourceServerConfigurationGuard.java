package io.github.ande1922.moduvera.security.autoconfigure;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;

public final class ResourceServerConfigurationGuard implements InitializingBean {

    private final OAuth2ResourceServerProperties properties;

    public ResourceServerConfigurationGuard(OAuth2ResourceServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        var jwt = properties.getJwt();
        if (jwt.getIssuerUri() == null || jwt.getIssuerUri().isBlank()) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.issuer-uri is required");
        }
        if (jwt.getAudiences() == null || jwt.getAudiences().isEmpty()) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.audiences is required");
        }
    }
}
