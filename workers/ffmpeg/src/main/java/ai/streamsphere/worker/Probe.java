package ai.streamsphere.worker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ffprobe-based validation of untrusted input: decompression-bomb and abuse limits are enforced here. */
public final class Probe {
    public record Info(double durationSeconds, int width, int height, double fps, boolean hasAudio, String videoCodec) {}

    static final int MAX_W = 4096, MAX_H = 2304, MAX_STREAMS = 16;
    static final double MAX_DURATION = 3 * 3600;

    private Probe() {}

    public static Info probe(Path file, JsonMapper json) {
        var r = Proc.run(List.of("ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
                "-probesize", "50M", "-analyzeduration", "50M", file.toString()), file.getParent(), Duration.ofSeconds(90));
        if (r.exitCode() != 0) throw new Proc.ProcFailure("invalid media: ffprobe failed: " + last(r.stderrTail()), false);
        JsonNode root = json.readTree(r.stdout());
        JsonNode streams = root.path("streams");
        if (streams.size() > MAX_STREAMS) throw new Proc.ProcFailure("invalid media: too many streams", false);
        JsonNode video = null;
        boolean audio = false;
        for (JsonNode s : streams) {
            String type = s.path("codec_type").asString("");
            if (type.equals("video") && video == null && !s.path("disposition").path("attached_pic").asBoolean(false)) video = s;
            if (type.equals("audio")) audio = true;
        }
        if (video == null) throw new Proc.ProcFailure("invalid media: no video stream", false);
        int w = video.path("width").asInt(0), h = video.path("height").asInt(0);
        if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) throw new Proc.ProcFailure("invalid media: unsupported dimensions " + w + "x" + h, false);
        double duration = root.path("format").path("duration").asDouble(video.path("duration").asDouble(0));
        if (duration <= 0.5 || duration > MAX_DURATION) throw new Proc.ProcFailure("invalid media: duration " + duration + " s outside limits", false);
        double fps = parseRate(video.path("avg_frame_rate").asString("0/0"));
        if (fps <= 0 || fps > 120) fps = parseRate(video.path("r_frame_rate").asString("30/1"));
        if (fps <= 0 || fps > 120) fps = 30;
        return new Info(duration, w, h, fps, audio, video.path("codec_name").asString("unknown"));
    }

    static double parseRate(String r) {
        String[] p = r.split("/");
        try {
            double n = Double.parseDouble(p[0]), d = p.length > 1 ? Double.parseDouble(p[1]) : 1;
            return d == 0 ? 0 : n / d;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String last(String s) {
        String[] lines = s.strip().split("\n");
        return lines[lines.length - 1];
    }
}
