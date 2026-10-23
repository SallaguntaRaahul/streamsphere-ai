package ai.streamsphere.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Partial / malformed outputs must never be accepted (uploading only happens after validation). */
class HlsValidatorTest {
    @Test
    void unfinishedPlaylistRejected(@TempDir Path d) throws Exception {
        Files.writeString(d.resolve("index.m3u8"), "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:6.0,\nseg_00000.ts\n");
        Files.write(d.resolve("seg_00000.ts"), new byte[]{0x47});
        assertThatThrownBy(() -> HlsValidator.validateVariant(d, 6, 6)).hasMessageContaining("not finalized");
    }

    @Test
    void missingSegmentRejected(@TempDir Path d) throws Exception {
        Files.writeString(d.resolve("index.m3u8"), "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:6.0,\nseg_00000.ts\n#EXT-X-ENDLIST\n");
        assertThatThrownBy(() -> HlsValidator.validateVariant(d, 6, 6)).hasMessageContaining("missing segment");
    }

    @Test
    void overlongSegmentRejected(@TempDir Path d) throws Exception {
        Files.writeString(d.resolve("index.m3u8"), "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:12.0,\nseg_00000.ts\n#EXT-X-ENDLIST\n");
        Files.write(d.resolve("seg_00000.ts"), new byte[]{0x47});
        assertThatThrownBy(() -> HlsValidator.validateVariant(d, 6, 12)).hasMessageContaining("too long");
    }

    @Test
    void pathTraversalInPlaylistRejected(@TempDir Path d) throws Exception {
        Files.writeString(d.resolve("index.m3u8"), "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:6.0,\n../../etc/passwd\n#EXT-X-ENDLIST\n");
        assertThatThrownBy(() -> HlsValidator.validateVariant(d, 6, 6)).hasMessageContaining("unexpected segment path");
    }
}
