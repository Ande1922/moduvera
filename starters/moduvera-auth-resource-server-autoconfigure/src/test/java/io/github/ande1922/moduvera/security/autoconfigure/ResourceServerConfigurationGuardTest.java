package io.github.ande1922.moduvera.security.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;

class ResourceServerConfigurationGuardTest {

    @Test
    void requiresIssuerAndAudience() {
        var properties = new OAuth2ResourceServerProperties();

        assertThatThrownBy(() -> new ResourceServerConfigurationGuard(properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");

        properties.getJwt().setIssuerUri("https://identity.example.test");
        assertThatThrownBy(() -> new ResourceServerConfigurationGuard(properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audiences");
    }

    @Test
    void acceptsAnExplicitIssuerAndAudience() {
        var properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri("https://identity.example.test");
        properties.getJwt().setAudiences(List.of("moduvera-api"));

        assertThatCode(() -> new ResourceServerConfigurationGuard(properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
