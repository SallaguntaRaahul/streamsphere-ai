package ai.streamsphere.media;

/** How a transcode job is started. Selected by streamsphere.media.launcher (queue | k8s-job). */
public interface TranscodeLauncher {
    String name();

    /** Called inside the business transaction. Returns an external reference (e.g. Kubernetes Job name). */
    String launch(Contracts.TranscodeRequested request);
}
