package ai.streamsphere.media;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** No-charge defaults: every paid adapter refuses to start unless BOTH flags are explicitly enabled. */
class PaidProvidersTest {
    @ParameterizedTest
    @ValueSource(strings = {"mediaconvert", "mux", "cloudflare-stream"})
    void disabledByDefault(String provider) {
        assertThatThrownBy(() -> PaidProviders.requireApproval(provider, false, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaidProviders.requireApproval(provider, true, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaidProviders.requireApproval(provider, false, true)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> PaidProviders.requireApproval(provider, true, true)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mediaconvert", "mux", "cloudflare-stream"})
    void stubNeverCallsARealApi(String provider) {
        assertThatThrownBy(() -> new PaidProviders.PaidLauncher(provider).launch(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
