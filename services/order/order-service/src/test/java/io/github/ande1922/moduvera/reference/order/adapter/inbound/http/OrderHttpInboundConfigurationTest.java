package io.github.ande1922.moduvera.reference.order.adapter.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ande1922.moduvera.reference.order.application.OrderNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OrderHttpInboundConfigurationTest {

    @Test
    void keepsOrderNotFoundStatusProviderOwned() {
        var contributor = new OrderHttpInboundConfiguration().orderProblemStatusContributor();

        assertThat(contributor.statusFor(new OrderNotFoundException(42)))
                .contains(HttpStatus.NOT_FOUND);
    }
}
