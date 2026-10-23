package ai.streamsphere.playback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Compact HMAC-SHA256 token carried in the playback URL path: sessionId.titleId.assetId.expEpoch.signature.
 * Contains no personal data. This is access control, not DRM.
 */
public final class PlaybackToken {
    public record Claims(UUID sessionId, UUID titleId, UUID assetId, Instant expiresAt) {}

    private final byte[] key;

    public PlaybackToken(String secret) {
        if (secret == null || secret.length() < 32) throw new IllegalStateException("playback token secret must be >= 32 chars");
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(Claims c) {
        String body = b64(c.sessionId() + "." + c.titleId() + "." + c.assetId() + "." + c.expiresAt().getEpochSecond());
        return body + "." + b64(mac(body));
    }

    /** Returns claims or throws IllegalArgumentException (bad format / signature / expired). */
    public Claims verify(String token, Instant now) {
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || token.length() > 400) throw new IllegalArgumentException("malformed token");
        String body = token.substring(0, dot);
        byte[] sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        if (!MessageDigest.isEqual(sig, mac(body))) throw new IllegalArgumentException("bad signature");
        String[] p = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split("\\.");
        if (p.length != 4) throw new IllegalArgumentException("malformed token");
        Claims c = new Claims(UUID.fromString(p[0]), UUID.fromString(p[1]), UUID.fromString(p[2]), Instant.ofEpochSecond(Long.parseLong(p[3])));
        if (!now.isBefore(c.expiresAt())) throw new IllegalArgumentException("expired");
        return c;
    }

    private byte[] mac(String body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) { return b64(s.getBytes(StandardCharsets.UTF_8)); }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
