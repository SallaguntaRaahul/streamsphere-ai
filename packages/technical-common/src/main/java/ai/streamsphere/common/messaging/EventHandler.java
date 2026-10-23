package ai.streamsphere.common.messaging;

import java.util.Set;

/** Consumer-side handler. Runs inside a DB transaction together with inbox deduplication. */
public interface EventHandler {
    /** Routing keys handled, e.g. "UploadCompleted.v1". */
    Set<String> routingKeys();

    void handle(EventEnvelope event);
}
