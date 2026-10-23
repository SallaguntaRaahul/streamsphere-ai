package ai.streamsphere.media;

import ai.streamsphere.common.messaging.OutboxWriter;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class Launchers {

    /** Compose profile: durable request on RabbitMQ (via outbox); a worker with prefetch 1 consumes it. */
    @Bean
    @ConditionalOnProperty(name = "streamsphere.media.launcher", havingValue = "queue", matchIfMissing = true)
    TranscodeLauncher queueLauncher(OutboxWriter outbox) {
        return new TranscodeLauncher() {
            public String name() { return "queue"; }
            public String launch(Contracts.TranscodeRequested r) {
                outbox.append("TranscodeRequested", 1, r.assetId().toString(), r);
                return "queue:" + r.jobId();
            }
        };
    }

    /** Kubernetes profile: one bounded Job per attempt, running the same worker image in one-shot mode. */
    @Bean
    @ConditionalOnProperty(name = "streamsphere.media.launcher", havingValue = "k8s-job")
    TranscodeLauncher k8sJobLauncher(JsonMapper json,
                                     @Value("${streamsphere.media.k8s.namespace:streamsphere}") String ns,
                                     @Value("${streamsphere.media.k8s.image}") String image,
                                     @Value("${streamsphere.media.k8s.cpu-limit:1500m}") String cpu,
                                     @Value("${streamsphere.media.k8s.memory-limit:1536Mi}") String mem,
                                     @Value("${streamsphere.media.k8s.deadline-seconds:3600}") long deadline,
                                     @Value("${streamsphere.media.k8s.secret-name:ffmpeg-worker}") String secret) {
        KubernetesClient k8s = new KubernetesClientBuilder().build();
        return new TranscodeLauncher() {
            public String name() { return "k8s-job"; }
            public String launch(Contracts.TranscodeRequested r) {
                String name = "transcode-" + r.jobId().toString().substring(0, 8) + "-a" + r.attempt();
                String payload = Base64.getEncoder().encodeToString(json.writeValueAsBytes(r));
                Job job = new JobBuilder()
                        .withNewMetadata().withName(name).withNamespace(ns)
                            .addToLabels("app.kubernetes.io/name", "ffmpeg-worker").addToLabels("streamsphere/job-id", r.jobId().toString()).endMetadata()
                        .withNewSpec().withBackoffLimit(0).withActiveDeadlineSeconds(deadline).withTtlSecondsAfterFinished(900)
                            .withNewTemplate().withNewMetadata().addToLabels("app.kubernetes.io/name", "ffmpeg-worker").endMetadata()
                            .withNewSpec().withRestartPolicy("Never").withAutomountServiceAccountToken(false)
                                .withNewSecurityContext().withRunAsNonRoot(true).withRunAsUser(10001L).withFsGroup(10001L).endSecurityContext()
                                .addNewContainer().withName("ffmpeg").withImage(image).withArgs("--mode=oneshot")
                                    .addNewEnv().withName("JOB_PAYLOAD").withValue(payload).endEnv()
                                    .addNewEnvFrom().withNewSecretRef().withName(secret).endSecretRef().endEnvFrom()
                                    .withNewResources()
                                        .addToRequests("cpu", new Quantity("500m")).addToRequests("memory", new Quantity("512Mi"))
                                        .addToLimits("cpu", new Quantity(cpu)).addToLimits("memory", new Quantity(mem)).endResources()
                                    .withNewSecurityContext().withReadOnlyRootFilesystem(true).withAllowPrivilegeEscalation(false)
                                        .withNewCapabilities().addToDrop("ALL").endCapabilities().endSecurityContext()
                                    .addNewVolumeMount().withName("work").withMountPath("/work").endVolumeMount()
                                    .addNewVolumeMount().withName("tmp").withMountPath("/tmp").endVolumeMount()
                                .endContainer()
                                .addNewVolume().withName("work").withNewEmptyDir().withSizeLimit(new Quantity("8Gi")).endEmptyDir().endVolume()
                                .addNewVolume().withName("tmp").withNewEmptyDir().endEmptyDir().endVolume()
                            .endSpec().endTemplate()
                        .endSpec().build();
                k8s.batch().v1().jobs().inNamespace(ns).resource(job).create();
                return "k8s:" + ns + "/" + name;
            }
        };
    }
}
