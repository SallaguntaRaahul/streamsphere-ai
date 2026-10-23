package ai.streamsphere.upload;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Container sniffing from the first bytes of the stored object — we never trust the client's
 * Content-Type. Deep validation (codecs, duration, dimensions) happens in the FFmpeg worker.
 */
public final class MediaSignature {
    private MediaSignature() {}

    public static String detect(byte[] head) {
        if (head.length >= 12) {
            String box = new String(Arrays.copyOfRange(head, 4, 8), StandardCharsets.ISO_8859_1);
            if (box.equals("ftyp")) {
                String brand = new String(Arrays.copyOfRange(head, 8, 12), StandardCharsets.ISO_8859_1);
                return brand.startsWith("qt") ? "video/quicktime" : "video/mp4";
            }
            if (box.equals("moov") || box.equals("mdat") || box.equals("wide") || box.equals("free")) return "video/quicktime";
        }
        if (head.length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
            String s = new String(head, StandardCharsets.ISO_8859_1);
            return s.contains("webm") ? "video/webm" : "video/x-matroska";
        }
        return null;
    }
}
