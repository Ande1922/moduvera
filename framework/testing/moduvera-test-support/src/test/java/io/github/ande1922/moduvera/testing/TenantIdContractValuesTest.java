package io.github.ande1922.moduvera.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.context.TenantId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TenantIdContractValuesTest {

    @ParameterizedTest
    @MethodSource("io.github.ande1922.moduvera.testing.TenantIdContractValues#validTenantIds")
    void validValuesAreAcceptedByTheCanonicalTenantId(String value) {
        assertThat(new TenantId(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @MethodSource("io.github.ande1922.moduvera.testing.TenantIdContractValues#invalidTenantIds")
    void invalidValuesAreRejectedByTheCanonicalTenantId(String value) {
        assertThatThrownBy(() -> new TenantId(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
