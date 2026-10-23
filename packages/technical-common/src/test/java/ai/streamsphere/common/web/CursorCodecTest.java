package ai.streamsphere.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorCodecTest {
    @Test
    void roundTripsWithNanoPrecision() {
        Instant at = Instant.parse("2026-10-22T10:15:30.123456789Z");
        UUID id = UUID.randomUUID();
        var c = CursorCodec.decode(CursorCodec.encode(at, id));
        assertThat(c.at()).isEqualTo(at);
        assertThat(c.id()).isEqualTo(id);
    }

    @Test
    void rejectsTamperedCursorAsBadRequest() {
        assertThatThrownBy(() -> CursorCodec.decode("bm90LWEtY3Vyc29y"))
                .isInstanceOf(ApiException.class).hasMessageContaining("cursor");
    }

    @Test
    void blankMeansFirstPage() { assertThat(CursorCodec.decode(" ")).isNull(); }
}
