package io.github.ande1922.moduvera.reference.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AllOrNothingReservationPolicyTest {

    private final ReservationPolicy policy = new AllOrNothingReservationPolicy();

    @Test
    void reservesTheWholeRequestWhenEveryProductHasEnoughStockIncludingExactBoundaries() {
        ReservationDecision decision = policy.decide(
                List.of(line(8, Integer.MAX_VALUE), line(7, 2)),
                List.of(stock(7, 2), stock(8, Integer.MAX_VALUE)));

        assertThat(decision).isEqualTo(ReservationDecision.reserved());
    }

    @Test
    void rejectsTheWholeRequestWhenAnyProductIsMissing() {
        ReservationDecision decision = policy.decide(
                List.of(line(9, 1), line(7, 2), line(8, 1)),
                List.of(stock(8, 1)));

        assertThat(decision)
                .isEqualTo(ReservationDecision.rejected(List.of(7L, 9L)));
    }

    @Test
    void rejectsTheWholeRequestAndReportsEveryInsufficientProductInStableOrder() {
        ReservationDecision decision = policy.decide(
                List.of(line(9, 3), line(7, 1), line(8, 2)),
                List.of(stock(9, 2), stock(7, 4), stock(8, 0)));

        assertThat(decision)
                .isEqualTo(ReservationDecision.rejected(List.of(8L, 9L)));
    }

    @Test
    void requestRejectsDuplicateProductsBeforeAReservationDecision() {
        assertThatThrownBy(() -> new ReservationRequest(
                        "reserve-1", 1, List.of(line(7, 1), line(7, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only once");
    }

    private static ReservationRequestLine line(long productId, int quantity) {
        return new ReservationRequestLine(productId, quantity);
    }

    private static StockAvailability stock(long productId, int available) {
        return new StockAvailability(productId, available);
    }
}
