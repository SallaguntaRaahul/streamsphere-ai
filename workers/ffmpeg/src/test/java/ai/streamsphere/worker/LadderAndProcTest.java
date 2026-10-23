package ai.streamsphere.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LadderAndProcTest {
    @Test
    void ladderNeverUpscales() {
        assertThat(Ladder.forSource(720, 720)).extracting(Ladder.Rung::name).containsExactly("360p", "480p", "720p");
        assertThat(Ladder.forSource(1080, 720)).extracting(Ladder.Rung::name).containsExactly("360p", "480p", "720p");
        assertThat(Ladder.forSource(1080, 1080)).extracting(Ladder.Rung::name).containsExactly("360p", "480p", "720p", "1080p");
        assertThat(Ladder.forSource(360, 720)).extracting(Ladder.Rung::name).containsExactly("360p");
    }

    @Test
    void tinySourceGetsSingleNativeRung() {
        var l = Ladder.forSource(241, 720);
        assertThat(l).hasSize(1);
        assertThat(l.getFirst().height()).isEqualTo(240);
    }

    @Test
    void widthsAreEvenAndAspectPreserving() {
        assertThat(Ladder.widthFor(1920, 1080, 360)).isEqualTo(640);
        assertThat(Ladder.widthFor(1280, 720, 480)).isEqualTo(854);
        assertThat(Ladder.widthFor(1080, 1920, 360) % 2).isZero();
    }

    @Test
    void frameRateParsing() {
        assertThat(Probe.parseRate("30000/1001")).isBetween(29.96, 29.98);
        assertThat(Probe.parseRate("0/0")).isZero();
        assertThat(Probe.parseRate("garbage")).isZero();
    }

    @Test
    void argvIsNeverInterpretedByAShell(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("pwned");
        // if this went through a shell, the second command would create the marker file
        var r = Proc.run(List.of("echo", "safe; touch " + marker), dir, Duration.ofSeconds(5));
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).contains("safe; touch");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void timeoutKillsRunawayProcess(@TempDir Path dir) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Proc.run(List.of("sleep", "10"), dir, Duration.ofMillis(300)))
                .isInstanceOf(Proc.ProcFailure.class).hasMessageContaining("timed out");
    }
}
