package ai.streamsphere.worker;

import java.util.ArrayList;
import java.util.List;

/** Source-aware ABR ladder: never upscale; always at least one rung. */
public final class Ladder {
    public record Rung(String name, int height, int videoKbps, int audioKbps) {}

    static final List<Rung> FULL = List.of(
            new Rung("360p", 360, 800, 96),
            new Rung("480p", 480, 1400, 128),
            new Rung("720p", 720, 2800, 128),
            new Rung("1080p", 1080, 5000, 160));

    private Ladder() {}

    public static List<Rung> forSource(int sourceHeight, int maxHeight) {
        List<Rung> out = new ArrayList<>();
        for (Rung r : FULL) if (r.height() <= sourceHeight && r.height() <= maxHeight) out.add(r);
        if (out.isEmpty()) {
            int h = Math.max(2, sourceHeight - (sourceHeight % 2));
            out.add(new Rung(h + "p", h, 600, 96));
        }
        return out;
    }

    /** Even width preserving aspect ratio. */
    public static int widthFor(int srcW, int srcH, int h) {
        int w = (int) Math.round((double) srcW * h / srcH);
        return w + (w % 2);
    }
}
