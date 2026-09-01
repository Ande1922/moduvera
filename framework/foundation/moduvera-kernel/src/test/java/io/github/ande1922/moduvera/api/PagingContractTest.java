package io.github.ande1922.moduvera.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class PagingContractTest {

    @Test
    void calculatesStableOffsetPageMetadataWithoutLeakingAnOrmType() {
        PageRequest request = new PageRequest(1, 2, List.of(new SortCriterion("createdAt", SortDirection.DESC)));

        PageResult<String> result = PageResult.of(List.of("c", "d"), request, 5);

        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.content()).containsExactly("c", "d");
    }

    @Test
    void boundsAllCallerControlledSizes() {
        assertThatIllegalArgumentException().isThrownBy(() -> PageRequest.of(0, PageRequest.MAX_SIZE + 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new CursorRequest(null, 0));
    }

    @Test
    void cursorPresenceDefinesWhetherMoreDataExists() {
        assertThat(new CursorResult<>(List.of("a"), "opaque-next").hasMore()).isTrue();
        assertThat(new CursorResult<>(List.of("a"), null).hasMore()).isFalse();
    }
}
