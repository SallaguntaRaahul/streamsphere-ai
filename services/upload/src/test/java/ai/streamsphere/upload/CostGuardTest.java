package ai.streamsphere.upload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CostGuardTest {
    @ParameterizedTest
    @CsvSource({"0.0,OK", "0.49,OK", "0.50,NOTICE", "0.70,WARNING", "0.85,CRITICAL", "0.95,STOP", "1.2,STOP"})
    void thresholdLevelsMatchCostModel(double ratio, String level) {
        assertThat(CostGuard.level(ratio)).isEqualTo(level);
    }
}
