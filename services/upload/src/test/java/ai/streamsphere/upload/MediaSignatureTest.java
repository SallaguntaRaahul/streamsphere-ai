package ai.streamsphere.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MediaSignatureTest {
    static final Path FIX = Path.of("../../tests/fixtures/media");

    @Test
    void detectsRealMp4Fixture() throws Exception {
        byte[] head = java.util.Arrays.copyOf(Files.readAllBytes(FIX.resolve("fixture-720p-20s.mp4")), 4096);
        assertThat(MediaSignature.detect(head)).isEqualTo("video/mp4");
    }

    @Test
    void rejectsRandomBytesAndExecutables() throws Exception {
        assertThat(MediaSignature.detect(java.util.Arrays.copyOf(Files.readAllBytes(FIX.resolve("corrupted-random.bin")), 4096))).isNull();
        assertThat(MediaSignature.detect(new byte[]{0x4d, 0x5a, (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0})).isNull(); // PE "MZ"
        assertThat(MediaSignature.detect(new byte[]{0x7f, 'E', 'L', 'F'})).isNull();
    }

    @Test
    void detectsWebmAndMatroska() {
        byte[] ebml = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 'w', 'e', 'b', 'm'};
        assertThat(MediaSignature.detect(ebml)).isEqualTo("video/webm");
        assertThat(MediaSignature.detect(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 'm', 'k', 'v', ' '})).isEqualTo("video/x-matroska");
    }
}
