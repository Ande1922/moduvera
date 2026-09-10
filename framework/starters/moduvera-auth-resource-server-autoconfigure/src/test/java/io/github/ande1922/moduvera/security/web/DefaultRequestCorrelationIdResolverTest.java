package io.github.ande1922.moduvera.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DefaultRequestCorrelationIdResolverTest {

    private final DefaultRequestCorrelationIdResolver resolver =
            new DefaultRequestCorrelationIdResolver();

    @Test
    void reusesCorrelationIdEstablishedByTheWebStarter() {
        var request = new MockHttpServletRequest();
        request.addHeader(DefaultRequestCorrelationIdResolver.HEADER, "corr-42");
        io.github.ande1922.moduvera.web.ServletRequestDiagnostics.establish(request, true);

        assertThat(resolver.resolve(request)).isEqualTo("corr-42");
    }

    @Test
    void ignoresPublicCorrelationAndReusesTheRequestState() {
        var request = new MockHttpServletRequest();
        request.addHeader(DefaultRequestCorrelationIdResolver.HEADER, "corr-header");

        String generated = resolver.resolve(request);
        assertThat(generated).isNotEqualTo("corr-header");
        assertThat(java.util.UUID.fromString(generated).version()).isEqualTo(4);
        assertThat(resolver.resolve(request)).isEqualTo(generated);
    }

    @Test
    void establishesAndReusesOneGeneratedValue() {
        var request = new MockHttpServletRequest();

        String generated = resolver.resolve(request);

        assertThat(generated).isNotBlank();
        assertThat(request.getAttribute(DefaultRequestCorrelationIdResolver.REQUEST_ATTRIBUTE))
                .isEqualTo(generated);
        assertThat(resolver.resolve(request)).isEqualTo(generated);
    }
}
