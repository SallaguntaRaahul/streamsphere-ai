package ai.streamsphere.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates packaged output before anything is uploaded: playlist syntax, segment presence, segment
 * duration bound, total duration, and that segments start on a keyframe (alignment across renditions).
 */
public final class HlsValidator {
    private HlsValidator() {}

    public static int validateVariant(Path dir, int segmentSeconds, double sourceDuration) throws IOException {
        Path pl = dir.resolve("index.m3u8");
        if (!Files.exists(pl)) throw new Proc.ProcFailure("validation: missing playlist " + pl, true);
        List<String> lines = Files.readAllLines(pl);
        if (lines.isEmpty() || !lines.getFirst().equals("#EXTM3U")) throw new Proc.ProcFailure("validation: bad playlist header", true);
        if (lines.stream().noneMatch(l -> l.equals("#EXT-X-ENDLIST"))) throw new Proc.ProcFailure("validation: playlist not finalized", true);
        if (lines.stream().noneMatch(l -> l.startsWith("#EXT-X-PLAYLIST-TYPE:VOD"))) throw new Proc.ProcFailure("validation: not VOD", true);
        double total = 0;
        List<String> segs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("#EXTINF:")) {
                double d = Double.parseDouble(l.substring(8, l.indexOf(',')));
                if (d > segmentSeconds * 1.5 + 0.1) throw new Proc.ProcFailure("validation: segment too long (" + d + " s)", true);
                total += d;
                String seg = lines.get(i + 1);
                if (seg.contains("/") || seg.contains("..")) throw new Proc.ProcFailure("validation: unexpected segment path", false);
                if (!Files.exists(dir.resolve(seg)) || Files.size(dir.resolve(seg)) == 0) throw new Proc.ProcFailure("validation: missing segment " + seg, true);
                segs.add(seg);
            }
        }
        if (segs.isEmpty()) throw new Proc.ProcFailure("validation: no segments", true);
        if (Math.abs(total - sourceDuration) > Math.max(1.5, sourceDuration * 0.02)) {
            throw new Proc.ProcFailure("validation: duration " + total + " s differs from source " + sourceDuration + " s", true);
        }
        // keyframe at the start of first, middle and last segment
        for (String seg : List.of(segs.getFirst(), segs.get(segs.size() / 2), segs.getLast())) {
            var r = Proc.run(List.of("ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1",
                    "-show_entries", "frame=key_frame", "-of", "csv=p=0", seg), dir, Duration.ofSeconds(30));
            if (r.exitCode() != 0 || !r.stdout().strip().startsWith("1")) {
                throw new Proc.ProcFailure("validation: segment " + seg + " does not start with a keyframe", true);
            }
        }
        return segs.size();
    }
}
