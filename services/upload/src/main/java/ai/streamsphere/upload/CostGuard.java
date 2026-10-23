package ai.streamsphere.upload;

import ai.streamsphere.common.web.ApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Application-side storage/operation meter. It ESTIMATES provider usage and fails closed; it is not
 * a billing cap (see docs/cost/COST_MODEL.md).
 */
@Component
public class CostGuard {
    private static final Logger log = LoggerFactory.getLogger(CostGuard.class);
    private final JdbcClient jdbc;
    private final UploadProperties props;
    private final AtomicReference<Double> lastRatio = new AtomicReference<>(0.0);

    public CostGuard(JdbcClient jdbc, UploadProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.props = props;
        Gauge.builder("streamsphere_cost_quota_ratio", lastRatio, AtomicReference::get).register(meters);
    }

    public record Usage(long sourceBytes, long mediaBytes, long quotaBytes, double ratio, String level,
                        Map<String, Long> operationsThisMonth) {}

    public Usage usage() {
        long source = jdbc.sql("select coalesce(sum(size_bytes),0) from upload_sessions where state in ('UPLOADING','UPLOADED','INITIATED') and source_deleted_at is null")
                .query(Long.class).single();
        long media = jdbc.sql("select coalesce(sum(bytes),0) from media_storage").query(Long.class).single();
        double ratio = props.storageQuotaBytes() <= 0 ? 1.0 : (double) (source + media) / props.storageQuotaBytes();
        lastRatio.set(ratio);
        Map<String, Long> ops = new java.util.TreeMap<>();
        jdbc.sql("select op_class, count from cost_meter where month = :m").param("m", month())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list().forEach(e -> ops.put(e.getKey(), e.getValue()));
        return new Usage(source, media, props.storageQuotaBytes(), ratio, level(ratio), ops);
    }

    static String level(double r) {
        if (r >= 0.95) return "STOP";
        if (r >= 0.85) return "CRITICAL";
        if (r >= 0.70) return "WARNING";
        if (r >= 0.50) return "NOTICE";
        return "OK";
    }

    /** Rejects an upload that would cross the safety threshold. Any metering failure also rejects (fail closed). */
    public void admit(long incomingBytes) {
        Usage u;
        try {
            u = usage();
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "cost-meter-unavailable", "Usage meter unavailable; uploads paused (fail-closed)");
        }
        double projected = (double) (u.sourceBytes() + u.mediaBytes() + incomingBytes * 2) / u.quotaBytes(); // ×2: source + renditions
        if (u.ratio() >= 0.70) log.warn("Storage estimate at {}% of application quota ({})", Math.round(u.ratio() * 100), u.level());
        if (projected >= props.stopRatio()) {
            throw ApiException.conflict("quota-exhausted", "Platform storage quota reached; new uploads are paused");
        }
    }

    public void countOps(String opClass, long n) {
        jdbc.sql("""
                insert into cost_meter(month, op_class, count) values (:m, :c, :n)
                on conflict (month, op_class) do update set count = cost_meter.count + excluded.count""")
            .param("m", month()).param("c", opClass).param("n", n).update();
    }

    static String month() { return YearMonth.now(ZoneOffset.UTC).toString(); }
}
