package ai.streamsphere.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaybackTokenTest {
    final PlaybackToken tokens = new PlaybackToken("test-secret-test-secret-test-secret-0123");
    final PlaybackToken.Claims claims = new PlaybackToken.Claims(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)); // tokens carry whole seconds

    @Test
    void validTokenRoundTrips() {
        assertThat(tokens.verify(tokens.issue(claims), Instant.now())).isEqualTo(claims);
    }

    @Test
    void expiredTokenRejected() {
        String t = tokens.issue(claims);
        assertThatThrownBy(() -> tokens.verify(t, Instant.now().plusSeconds(61))).hasMessage("expired");
    }

    @Test
    void tamperedPayloadOrSignatureRejected() {
        String t = tokens.issue(claims);
        String other = tokens.issue(new PlaybackToken.Claims(claims.sessionId(), UUID.randomUUID(), claims.assetId(), claims.expiresAt()));
        String swapped = other.substring(0, other.lastIndexOf('.')) + t.substring(t.lastIndexOf('.'));
        assertThatThrownBy(() -> tokens.verify(swapped, Instant.now())).hasMessage("bad signature");
        assertThatThrownBy(() -> tokens.verify(t.substring(0, t.length() - 2) + "AA", Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenFromAnotherKeyRejected() {
        String foreign = new PlaybackToken("another-secret-another-secret-01234567").issue(claims);
        assertThatThrownBy(() -> tokens.verify(foreign, Instant.now())).hasMessage("bad signature");
    }

    @Test
    void weakSecretRefused() {
        assertThatThrownBy(() -> new PlaybackToken("short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void segmentNamePatternBlocksTraversal() {
        assertThat(PlaybackController.SEGMENT.matcher("seg_00001.ts").matches()).isTrue();
        assertThat(PlaybackController.SEGMENT.matcher("../seg_00001.ts").matches()).isFalse();
        assertThat(PlaybackController.SEGMENT.matcher("seg_00001.ts?x").matches()).isFalse();
        assertThat(PlaybackController.SEGMENT.matcher("_COMPLETE").matches()).isFalse();
    }
}
