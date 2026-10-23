package ai.streamsphere.common.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque keyset cursor (createdAt, id) for stable pagination. */
public final class CursorCodec {
    public record Cursor(Instant at, UUID id) {}

    private CursorCodec() {}

    public static String encode(Instant at, UUID id) {
        String raw = at.toEpochMilli() + ":" + at.getNano() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":");
            Instant at = Instant.ofEpochSecond(Long.parseLong(p[0]) / 1000, Integer.parseInt(p[1]));
            return new Cursor(at, UUID.fromString(p[2]));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid-cursor", "Malformed pagination cursor");
        }
    }
}
