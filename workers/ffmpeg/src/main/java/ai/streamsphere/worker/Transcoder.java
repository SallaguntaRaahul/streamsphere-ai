package ai.streamsphere.worker;

import ai.streamsphere.common.storage.StorageProvider;
import ai.streamsphere.worker.Messages.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** download → checksum → probe → encode ABR HLS → poster/thumbnails → validate → upload → _COMPLETE. */
public class Transcoder {
    private static final Logger log = LoggerFactory.getLogger(Transcoder.class);
    private static final Pattern BENCH = Pattern.compile("bench: utime=([0-9.]+)s stime=([0-9.]+)s");
    private final StorageProvider storage;
    private final JsonMapper json;
    private final Path workRoot;
    private final String faultInjection;

    public Transcoder(StorageProvider storage, JsonMapper json, Path workRoot, String faultInjection) {
        this.storage = storage;
        this.json = json;
        this.workRoot = workRoot;
        this.faultInjection = faultInjection == null ? "" : faultInjection;
    }

    public TranscodeCompleted run(TranscodeRequested req) throws IOException {
        long t0 = System.nanoTime();
        var existing = storage.head(req.outputBucket(), req.outputPrefix() + "_COMPLETE");
        if (existing.isPresent()) { // redelivery after a crash post-upload: re-announce, do not re-encode
            try (InputStream in = storage.open(req.outputBucket(), req.outputPrefix() + "_COMPLETE")) {
                log.info("Job {} already complete; re-announcing", req.jobId());
                return json.readValue(in.readAllBytes(), TranscodeCompleted.class);
            }
        }
        Path work = Files.createDirectories(workRoot.resolve(req.jobId().toString()));
        try {
            Path src = work.resolve("source");
            storage.download(req.sourceBucket(), req.sourceKey(), src);
            String sha = sha256(src);
            if (!sha.equalsIgnoreCase(req.sourceSha256())) {
                throw new Proc.ProcFailure("checksum mismatch: expected " + req.sourceSha256() + " got " + sha, false);
            }
            Probe.Info info = Probe.probe(src, json);
            log.info("Job {} probe: {}x{} {} fps {} s audio={} codec={}", req.jobId(), info.width(), info.height(),
                    String.format(Locale.ROOT, "%.2f", info.fps()), Math.round(info.durationSeconds()), info.hasAudio(), info.videoCodec());

            Path out = Files.createDirectories(work.resolve("out"));
            List<Ladder.Rung> ladder = Ladder.forSource(info.height(), req.maxHeight());
            int fps = (int) Math.min(30, Math.round(info.fps()));
            double cpu = encode(req, info, ladder, fps, src, out);
            List<String> extra = posterAndThumbs(src, out, info);

            List<RenditionResult> renditions = new ArrayList<>();
            long total = 0;
            for (Ladder.Rung r : ladder) {
                Path dir = out.resolve(r.name());
                int segs = HlsValidator.validateVariant(dir, req.segmentSeconds(), info.durationSeconds());
                long bytes = dirSize(dir);
                total += bytes;
                int w = Ladder.widthFor(info.width(), info.height(), r.height());
                int aKbps = info.hasAudio() ? r.audioKbps() : 0;
                renditions.add(new RenditionResult(r.name(), w, r.height(), r.videoKbps(), aKbps,
                        (int) ((r.videoKbps() * 1.1 + aKbps) * 1000), info.hasAudio() ? "avc1.4d401f,mp4a.40.2" : "avc1.4d401f",
                        req.outputPrefix() + r.name() + "/index.m3u8", segs, bytes));
            }
            total += dirSize(out.resolve("thumbs")) + (Files.exists(out.resolve("poster.jpg")) ? Files.size(out.resolve("poster.jpg")) : 0);
            if (faultInjection.equals("fail-before-upload")) throw new Proc.ProcFailure("fault injection: fail-before-upload", true);
            uploadTree(req, out);
            int thumbs = (int) extra.stream().filter(s -> s.startsWith("thumbs/")).count();
            var done = new TranscodeCompleted(req.jobId(), req.assetId(), req.outputPrefix(), (int) Math.round(info.durationSeconds()),
                    info.width(), info.height(), info.hasAudio(), renditions,
                    Files.exists(out.resolve("poster.jpg")) ? req.outputPrefix() + "poster.jpg" : null, thumbs, total,
                    (System.nanoTime() - t0) / 1e9, cpu, ffmpegVersion());
            // _COMPLETE is written LAST; its absence means the output must never be served.
            storage.put(req.outputBucket(), req.outputPrefix() + "_COMPLETE", json.writeValueAsBytes(done), "application/json");
            return done;
        } finally {
            deleteTree(work);
        }
    }

    private double encode(TranscodeRequested req, Probe.Info info, List<Ladder.Rung> ladder, int fps, Path src, Path out) {
        int gop = fps * 2; // 2-s GOP divides the 6-s segment → aligned keyframes in every rendition
        List<String> a = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-benchmark",
                "-i", src.toString()));
        StringBuilder fc = new StringBuilder("[0:v]fps=" + fps + ",split=" + ladder.size());
        for (int i = 0; i < ladder.size(); i++) fc.append("[s").append(i).append(']');
        fc.append(';');
        for (int i = 0; i < ladder.size(); i++) {
            fc.append("[s").append(i).append("]scale=-2:").append(ladder.get(i).height()).append(",format=yuv420p[v").append(i).append(']');
            if (i < ladder.size() - 1) fc.append(';');
        }
        a.addAll(List.of("-filter_complex", fc.toString()));
        StringBuilder map = new StringBuilder();
        for (int i = 0; i < ladder.size(); i++) {
            Ladder.Rung r = ladder.get(i);
            a.addAll(List.of("-map", "[v" + i + "]"));
            a.addAll(List.of("-c:v:" + i, "libx264", "-b:v:" + i, r.videoKbps() + "k", "-maxrate:v:" + i, (int) (r.videoKbps() * 1.1) + "k",
                    "-bufsize:v:" + i, (r.videoKbps() * 2) + "k"));
            if (info.hasAudio()) {
                a.addAll(List.of("-map", "0:a:0", "-c:a:" + i, "aac", "-b:a:" + i, r.audioKbps() + "k", "-ac:a:" + i, "2"));
                map.append("v:").append(i).append(",a:").append(i).append(",name:").append(r.name()).append(' ');
            } else {
                map.append("v:").append(i).append(",name:").append(r.name()).append(' ');
            }
        }
        a.addAll(List.of("-preset", "veryfast", "-profile:v", "main", "-level:v", "3.1", "-g", String.valueOf(gop),
                "-keyint_min", String.valueOf(gop), "-sc_threshold", "0", "-force_key_frames", "expr:gte(t,n_forced*2)",
                "-threads", "2",
                "-f", "hls", "-hls_time", String.valueOf(req.segmentSeconds()), "-hls_playlist_type", "vod",
                "-hls_flags", "independent_segments", "-hls_segment_type", "mpegts",
                "-hls_segment_filename", out + "/%v/seg_%05d.ts", "-master_pl_name", "master.m3u8",
                "-var_stream_map", map.toString().strip(), out + "/%v/index.m3u8"));
        Duration timeout = Duration.ofSeconds((long) Math.max(600, info.durationSeconds() * 8));
        if (faultInjection.equals("crash-mid-encode")) timeout = Duration.ofSeconds(3); // simulates a killed encoder
        var r = Proc.run(a, out, timeout);
        if (r.exitCode() != 0) {
            boolean bad = r.stderrTail().contains("Invalid data found") || r.stderrTail().contains("could not find codec");
            throw new Proc.ProcFailure("ffmpeg exited " + r.exitCode() + ": " + Probe.last(r.stderrTail()), !bad);
        }
        Matcher m = BENCH.matcher(r.stderrTail());
        return m.find() ? Double.parseDouble(m.group(1)) + Double.parseDouble(m.group(2)) : 0;
    }

    private List<String> posterAndThumbs(Path src, Path out, Probe.Info info) throws IOException {
        String at = String.format(Locale.ROOT, "%.2f", Math.min(5.0, info.durationSeconds() * 0.1));
        Proc.run(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-ss", at, "-i", src.toString(), "-frames:v", "1",
                "-vf", "scale=-2:720", "-q:v", "3", out.resolve("poster.jpg").toString()), out, Duration.ofSeconds(120));
        Files.createDirectories(out.resolve("thumbs"));
        Proc.run(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", src.toString(), "-vf", "fps=1/10,scale=-2:180",
                "-q:v", "5", out.resolve("thumbs/thumb_%04d.jpg").toString()), out, Duration.ofSeconds((long) Math.max(120, info.durationSeconds())));
        try (Stream<Path> s = Files.walk(out)) {
            return s.filter(Files::isRegularFile).map(p -> out.relativize(p).toString()).toList();
        }
    }

    private void uploadTree(TranscodeRequested req, Path out) throws IOException {
        try (Stream<Path> s = Files.walk(out)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = out.relativize(p).toString().replace('\\', '/');
                String type = rel.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : rel.endsWith(".ts") ? "video/mp2t"
                        : rel.endsWith(".jpg") ? "image/jpeg" : "application/octet-stream";
                storage.upload(req.outputBucket(), req.outputPrefix() + rel, p, type, "private, max-age=31536000, immutable");
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static long dirSize(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
        }
    }

    static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { }
    }

    static String ffmpegVersion() {
        var r = Proc.run(List.of("ffmpeg", "-version"), Path.of("/tmp"), Duration.ofSeconds(10));
        String first = r.stdout().lines().findFirst().orElse("ffmpeg unknown");
        return first.length() > 60 ? first.substring(0, 60) : first;
    }

    static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
