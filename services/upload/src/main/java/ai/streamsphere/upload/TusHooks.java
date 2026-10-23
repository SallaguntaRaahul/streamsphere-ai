package ai.streamsphere.upload;

import ai.streamsphere.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * tusd v2 HTTP hooks (local-full / any host running tusd). tusd stores bytes in the same private bucket; this
 * endpoint decides admission (pre-create) and verifies + publishes (post-finish). Not routed by the gateway;
 * authenticated by a shared secret, and the end user by the bearer token tusd forwards from the original request.
 */
@RestController
@RequestMapping("/api/v1/internal/tus/hooks")
public class TusHooks {
    private static final Logger log = LoggerFactory.getLogger(TusHooks.class);
    private final UploadService uploads;
    private final JwtDecoder jwt;
    private final byte[] secret;
    private final String bucket;

    public TusHooks(UploadService uploads, JwtDecoder jwt, @Value("${streamsphere.upload.tus-hook-secret:}") String secret,
                    @Value("${streamsphere.upload.bucket}") String bucket) {
        this.uploads = uploads;
        this.jwt = jwt;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.bucket = bucket;
    }

    @PostMapping
    public Map<String, Object> hook(@RequestParam("secret") String given, @RequestBody JsonNode body) {
        if (secret.length < 16 || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.forbidden("bad hook secret");
        }
        String type = body.path("Type").asString("");
        JsonNode upload = body.path("Event").path("Upload");
        try {
            authenticate(body.path("Event").path("HTTPRequest").path("Header").path("Authorization"));
            return switch (type) {
                case "pre-create" -> {
                    JsonNode md = upload.path("MetaData");
                    var s = uploads.registerTus(tusIdPlaceholder(upload), UUID.fromString(md.path("titleId").asString()),
                            md.path("filename").asString(null), md.path("filetype").asString(""), upload.path("Size").asLong(-1),
                            md.path("sha256").asString(null), bucket);
                    // fix the tus id to our session id so the object key is predictable and auditable
                    yield Map.of("ChangeFileInfo", Map.of("ID", s.id().toString(), "MetaData", Map.of("titleId", s.titleId().toString())));
                }
                case "post-finish" -> {
                    // s3store ids look like "<objectId>+<multipartUploadId>"; our session is keyed by objectId
                    String id = upload.path("ID").asString();
                    uploads.finishTus(id.contains("+") ? id.substring(0, id.indexOf('+')) : id, upload.path("Storage").path("Key").asString());
                    yield Map.of();
                }
                default -> Map.of();
            };
        } catch (ApiException e) {
            log.info("tus {} rejected: {}", type, e.getMessage());
            return reject(e.status().value(), e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            return reject(401, "authentication required");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** pre-create has no id yet; we register under a temporary id and immediately rename via ChangeFileInfo. */
    private static String tusIdPlaceholder(JsonNode upload) { return UUID.randomUUID().toString(); }

    private void authenticate(JsonNode authHeader) {
        String value = authHeader.isArray() && !authHeader.isEmpty() ? authHeader.get(0).asString() : null;
        if (value == null || !value.startsWith("Bearer ")) throw new IllegalArgumentException("no bearer");
        var token = jwt.decode(value.substring(7)); // signature, issuer, audience, expiry
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token, List.of()));
    }

    private static Map<String, Object> reject(int status, String message) {
        return Map.of("RejectUpload", true, "HTTPResponse", Map.of("StatusCode", status, "Body", "{\"detail\":\"" + message.replace("\"", "'") + "\"}",
                "Header", Map.of("Content-Type", "application/problem+json")));
    }
}
