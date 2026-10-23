package ai.streamsphere.profile;

import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/** Viewer profile and playback preferences. Created lazily from the token on first read. */
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {
    public record Profile(String userId, String displayName, String preferredLanguage, boolean captionsDefault,
                          boolean autoplay, boolean reducedMotion, Instant createdAt, int version) {}

    public record ProfileUpdate(@NotBlank @Size(max = 80) String displayName,
                                @Pattern(regexp = "[a-z]{2}(-[A-Z]{2})?") String preferredLanguage,
                                boolean captionsDefault, boolean autoplay, boolean reducedMotion) {}

    private final JdbcClient jdbc;

    public ProfileController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/me")
    public ResponseEntity<Profile> me() {
        String id = CurrentUser.id();
        jdbc.sql("""
                insert into profiles(user_id, display_name, preferred_language, created_at, updated_at, version)
                values (:u, :n, 'en', now(), now(), 0) on conflict (user_id) do nothing""")
            .param("u", id).param("n", CurrentUser.displayName()).update();
        Profile p = find(id);
        return ResponseEntity.ok().eTag("\"" + p.version() + "\"").body(p);
    }

    @PutMapping("/me")
    public ResponseEntity<Profile> update(@Valid @RequestBody ProfileUpdate u,
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        if (ifMatch == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "if-match-required", "Send If-Match");
        int n = jdbc.sql("""
                update profiles set display_name = :n, preferred_language = coalesce(:l, preferred_language),
                  captions_default = :c, autoplay = :a, reduced_motion = :r, updated_at = now(), version = version + 1
                where user_id = :u and version = :v""")
            .param("n", u.displayName().strip()).param("l", u.preferredLanguage()).param("c", u.captionsDefault())
            .param("a", u.autoplay()).param("r", u.reducedMotion()).param("u", CurrentUser.id())
            .param("v", Integer.parseInt(ifMatch.replace("\"", ""))).update();
        if (n == 0) throw ApiException.conflict("concurrent-modification", "Profile changed or does not exist; reload");
        Profile p = find(CurrentUser.id());
        return ResponseEntity.ok().eTag("\"" + p.version() + "\"").body(p);
    }

    /** Privacy: the user can erase their profile record. */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void erase() { jdbc.sql("delete from profiles where user_id = :u").param("u", CurrentUser.id()).update(); }

    private Profile find(String id) {
        return jdbc.sql("""
                select user_id, display_name, preferred_language, captions_default, autoplay, reduced_motion, created_at, version
                from profiles where user_id = :u""").param("u", id).query(Profile.class).optional()
            .orElseThrow(() -> ApiException.notFound("Profile"));
    }
}
