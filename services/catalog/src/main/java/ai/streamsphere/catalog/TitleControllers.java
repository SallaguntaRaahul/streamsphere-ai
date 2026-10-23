package ai.streamsphere.catalog;

import ai.streamsphere.catalog.Dtos.TitleRequest;
import ai.streamsphere.catalog.Dtos.TitleView;
import ai.streamsphere.common.cache.SafeCache;
import ai.streamsphere.common.security.CurrentUser;
import ai.streamsphere.common.web.ApiException;
import ai.streamsphere.common.web.CursorCodec;
import ai.streamsphere.common.web.PageResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
public class TitleControllers {
    private final TitleRepository repo;
    private final TitleService service;
    private final Optional<SafeCache> cache;
    private final JsonMapper json;

    public TitleControllers(TitleRepository repo, TitleService service, Optional<SafeCache> cache, JsonMapper json) {
        this.repo = repo;
        this.service = service;
        this.cache = cache;
        this.json = json;
    }

    // ------------------------------------------------------------------ public browse
    @GetMapping("/titles")
    public PageResponse<TitleView> browse(@RequestParam(required = false) String genre,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "24") int limit) {
        int lim = Math.max(1, Math.min(limit, 60));
        if (genre != null && !genre.matches("[a-z][a-z-]{1,30}")) throw ApiException.badRequest("invalid-genre", "Invalid genre");
        String key = "catalog:public:titles:" + genre + ":" + cursor + ":" + lim;
        var load = (java.util.function.Supplier<String>) () -> {
            var c = CursorCodec.decode(cursor);
            List<Title> rows = repo.published(genre, c == null ? null : c.at(), c == null ? null : c.id(), lim + 1);
            boolean more = rows.size() > lim;
            List<Title> page = more ? rows.subList(0, lim) : rows;
            String next = more ? CursorCodec.encode(page.getLast().publishedAt(), page.getLast().id()) : null;
            return json.writeValueAsString(new PageResponse<>(page.stream().map(TitleView::of).toList(), next));
        };
        String body = cache.map(c -> c.getOrLoad(key, Duration.ofSeconds(60), load)).orElseGet(load);
        return json.readValue(body, new TypeReference<PageResponse<TitleView>>() {});
    }

    @GetMapping("/titles/{id}")
    public TitleView get(@PathVariable UUID id) { return TitleView.of(service.visibleTo(id)); }

    @GetMapping("/titles/batch")
    public List<TitleView> batch(@RequestParam String ids) {
        List<UUID> list = Arrays.stream(ids.split(",")).limit(100).map(String::trim).filter(s -> !s.isEmpty()).map(UUID::fromString).toList();
        return repo.findPublishedByIds(list).stream().map(TitleView::of).toList();
    }

    @GetMapping("/genres")
    public List<TitleRepository.GenreCount> genres() { return repo.genres(); }

    // ------------------------------------------------------------------ creator workspace
    @PostMapping("/creator/titles")
    @ResponseStatus(HttpStatus.CREATED)
    public Title create(@Valid @RequestBody TitleRequest r) { return service.createDraft(r); }

    @GetMapping("/creator/titles")
    public List<Title> mine() { return repo.byCreator(CurrentUser.id()); }

    @GetMapping("/creator/titles/{id}")
    public Title mineOne(@PathVariable UUID id) {
        Title t = repo.find(id).orElseThrow(() -> ApiException.notFound("Title"));
        if (!t.ownedBy(CurrentUser.id())) throw ApiException.notFound("Title");
        return t;
    }

    @PutMapping("/creator/titles/{id}")
    public ResponseEntity<Title> update(@PathVariable UUID id, @Valid @RequestBody TitleRequest r,
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        if (ifMatch == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "if-match-required", "Send If-Match with the title version");
        Title t = service.update(id, r, Integer.parseInt(ifMatch.replace("\"", "")));
        return ResponseEntity.ok().eTag("\"" + t.version() + "\"").body(t);
    }

    @PostMapping("/creator/titles/{id}/submit")
    public Title submit(@PathVariable UUID id) { return service.submitForReview(id); }

    @PostMapping("/creator/titles/{id}/publish")
    public Title publish(@PathVariable UUID id) { return service.publish(id); }

    @DeleteMapping("/creator/titles/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delete(@PathVariable UUID id) { service.requestDeletion(id); }

    // ------------------------------------------------------------------ staff / internal
    @GetMapping("/catalog/admin/titles")
    @PreAuthorize("hasAnyRole('moderator','admin')")
    public List<Title> byState(@RequestParam EditorialState state) { return repo.byState(state, 200); }

    /** Internal: playback checks publication + ownership with a service token. */
    @GetMapping("/internal/titles/{id}")
    @PreAuthorize("hasRole('service')")
    public Title internal(@PathVariable UUID id) { return repo.find(id).orElseThrow(() -> ApiException.notFound("Title")); }
}
