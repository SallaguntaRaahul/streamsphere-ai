package ai.streamsphere.search;

import ai.streamsphere.common.messaging.EventEnvelope;
import ai.streamsphere.common.messaging.EventHandler;
import ai.streamsphere.common.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

/**
 * Hybrid search: PostgreSQL full-text (weighted title > creator/genre > synopsis) and pgvector cosine
 * similarity, fused with Reciprocal Rank Fusion (k = 60). Trigram similarity rescues typos when FTS finds
 * nothing. Every hit reports which signals matched.
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
    public record Hit(UUID titleId, String title, String synopsis, List<String> genres, String language, String creatorName,
                      Integer releaseYear, Integer durationSeconds, double score, Integer keywordRank, Integer semanticRank,
                      boolean fuzzy) {}
    public record Results(String query, String mode, boolean semanticAvailable, String embeddingModel, List<Hit> items,
                          String nextCursor, long tookMs) {}
    record Doc(UUID titleId, String title, String synopsis, List<String> genres, String language, String creatorName,
               Integer releaseYear, Integer durationSeconds) {}

    static final int K = 60, POOL = 100;
    private final JdbcClient jdbc;
    private final Embedder embedder;
    private final MeterRegistry meters;

    public SearchController(JdbcClient jdbc, Embedder embedder, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.meters = meters;
    }

    @GetMapping
    public Results search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String genre,
                          @RequestParam(required = false) String language, @RequestParam(required = false) Integer yearFrom,
                          @RequestParam(required = false) Integer yearTo, @RequestParam(defaultValue = "hybrid") String mode,
                          @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        long t0 = System.nanoTime();
        if (q.length() > 200) throw ApiException.badRequest("query-too-long", "Query must be ≤ 200 characters");
        if (!Set.of("hybrid", "keyword", "semantic").contains(mode)) throw ApiException.badRequest("invalid-mode", "mode = hybrid|keyword|semantic");
        int lim = Math.max(1, Math.min(limit, 50));
        int offset = parseOffset(cursor);
        String filter = " and (cast(:genre as text) is null or :genre = any(genres)) and (cast(:lang as text) is null or language = :lang)"
                + " and (cast(:yf as int) is null or release_year >= :yf) and (cast(:yt as int) is null or release_year <= :yt)";
        Map<UUID, double[]> fused = new LinkedHashMap<>(); // [score, kwRank, semRank, fuzzy]
        boolean semanticOk = false;
        String model = null;

        if (q.isBlank()) {
            List<UUID> ids = bind(jdbc.sql("select title_id from search_documents where true" + filter + " order by popularity desc, published_at desc limit :n"),
                    genre, language, yearFrom, yearTo).param("n", POOL).query(UUID.class).list();
            for (int i = 0; i < ids.size(); i++) fused.put(ids.get(i), new double[]{1.0 / (K + i + 1), 0, 0, 0});
        } else {
            if (!mode.equals("semantic")) {
                List<UUID> kw = bind(jdbc.sql("""
                        select title_id from search_documents, websearch_to_tsquery('english', :q) query
                        where tsv @@ query""" + filter + " order by ts_rank_cd(tsv, query) desc, popularity desc limit :n"),
                        genre, language, yearFrom, yearTo).param("q", q).param("n", POOL).query(UUID.class).list();
                for (int i = 0; i < kw.size(); i++) add(fused, kw.get(i), i, 1);
                if (kw.isEmpty()) { // typo tolerance
                    List<UUID> fz = bind(jdbc.sql("select title_id from search_documents where similarity(title, :q) > 0.2" + filter
                            + " order by similarity(title, :q) desc limit 20"), genre, language, yearFrom, yearTo).param("q", q).query(UUID.class).list();
                    for (int i = 0; i < fz.size(); i++) { add(fused, fz.get(i), i, 1); fused.get(fz.get(i))[3] = 1; }
                }
            }
            if (!mode.equals("keyword")) {
                var emb = embedder.embed(q);
                if (emb.isPresent()) {
                    semanticOk = true;
                    model = emb.get().model();
                    List<UUID> sem = bind(jdbc.sql("select title_id from search_documents where embedding is not null" + filter
                            + " and (embedding <=> cast(:v as vector)) < 0.8 order by embedding <=> cast(:v as vector) limit :n"),
                            genre, language, yearFrom, yearTo).param("v", vectorLiteral(emb.get().vector())).param("n", POOL)
                            .query(UUID.class).list();
                    for (int i = 0; i < sem.size(); i++) add(fused, sem.get(i), i, 2);
                }
            }
        }
        List<Map.Entry<UUID, double[]>> ranked = fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0])).toList();
        List<Map.Entry<UUID, double[]>> page = ranked.stream().skip(offset).limit(lim).toList();
        Map<UUID, Doc> docs = new HashMap<>();
        if (!page.isEmpty()) {
            jdbc.sql("select title_id, title, synopsis, genres, language, creator_name, release_year, duration_seconds from search_documents where title_id = any(:ids)")
                .param("ids", page.stream().map(Map.Entry::getKey).toArray(UUID[]::new))
                .query((rs, i) -> new Doc(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        Arrays.asList((String[]) rs.getArray(4).getArray()), rs.getString(5), rs.getString(6),
                        (Integer) rs.getObject(7), (Integer) rs.getObject(8))).list().forEach(d -> docs.put(d.titleId(), d));
        }
        List<Hit> hits = page.stream().filter(e -> docs.containsKey(e.getKey())).map(e -> {
            Doc d = docs.get(e.getKey());
            double[] v = e.getValue();
            return new Hit(d.titleId(), d.title(), d.synopsis(), d.genres(), d.language(), d.creatorName(), d.releaseYear(),
                    d.durationSeconds(), Math.round(v[0] * 1e5) / 1e5, v[1] > 0 ? (int) v[1] : null, v[2] > 0 ? (int) v[2] : null, v[3] > 0);
        }).toList();
        String next = offset + lim < ranked.size() ? Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + (offset + lim)).getBytes()) : null;
        long took = (System.nanoTime() - t0) / 1_000_000;
        meters.timer("streamsphere_search_latency", "mode", mode).record(java.time.Duration.ofMillis(took));
        return new Results(q, mode, semanticOk, model, hits, next, took);
    }

    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam String q) {
        if (q.length() < 2 || q.length() > 60) return List.of();
        return jdbc.sql("select title from search_documents where title ilike :p or similarity(title, :q) > 0.3 order by similarity(title, :q) desc limit 8")
                .param("p", q.replace("%", "").replace("_", "") + "%").param("q", q).query(String.class).list();
    }

    static String vectorLiteral(float[] v) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) b.append(','); b.append(v[i]); }
        return b.append(']').toString();
    }

    private static void add(Map<UUID, double[]> fused, UUID id, int rank, int signal) {
        double[] v = fused.computeIfAbsent(id, k -> new double[4]);
        v[0] += 1.0 / (K + rank + 1);
        v[signal] = rank + 1;
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec s, String genre, String lang, Integer yf, Integer yt) {
        return s.param("genre", genre).param("lang", lang).param("yf", yf).param("yt", yt);
    }

    private static int parseOffset(String cursor) {
        if (cursor == null) return 0;
        try {
            String s = new String(Base64.getUrlDecoder().decode(cursor));
            return Math.min(1000, Math.max(0, Integer.parseInt(s.substring(2))));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid-cursor", "Malformed cursor");
        }
    }

    /** Index maintenance from catalog events (no reads of catalog's database). */
    @Component
    static class Indexer implements EventHandler {
        private final JdbcClient jdbc;
        private final Embedder embedder;

        Indexer(JdbcClient jdbc, Embedder embedder) {
            this.jdbc = jdbc;
            this.embedder = embedder;
        }

        @Override public Set<String> routingKeys() { return Set.of("ContentPublished.v1", "ContentRemoved.v1", "PlaybackStarted.v1"); }

        @Override
        public void handle(EventEnvelope e) {
            var p = e.payload();
            UUID t = UUID.fromString(p.get("titleId").asString());
            switch (e.eventType()) {
                case "ContentPublished" -> {
                    List<String> genres = new ArrayList<>();
                    p.path("genres").forEach(g -> genres.add(g.asString()));
                    String title = p.get("title").asString(), synopsis = p.path("synopsis").asString(""), creator = p.path("creatorName").asString("");
                    jdbc.sql("""
                            insert into search_documents(title_id, title, synopsis, genres, language, creator_id, creator_name, release_year,
                              duration_seconds, maturity_rating, published_at, tsv, updated_at)
                            values (:t, :title, :syn, :g, :lang, :cid, :cn, :y, :d, :mr, :pub,
                              setweight(to_tsvector('english', :title), 'A') || setweight(to_tsvector('english', coalesce(:cn,'')), 'B')
                              || setweight(to_tsvector('simple', :gs), 'B') || setweight(to_tsvector('english', coalesce(:syn,'')), 'C'), now())
                            on conflict (title_id) do update set title = excluded.title, synopsis = excluded.synopsis, genres = excluded.genres,
                              language = excluded.language, creator_name = excluded.creator_name, release_year = excluded.release_year,
                              duration_seconds = excluded.duration_seconds, maturity_rating = excluded.maturity_rating,
                              published_at = excluded.published_at, tsv = excluded.tsv, updated_at = now()""")
                        .param("t", t).param("title", title).param("syn", synopsis).param("g", genres.toArray(String[]::new))
                        .param("gs", String.join(" ", genres)).param("lang", p.path("language").asString(null))
                        .param("cid", p.get("creatorId").asString()).param("cn", creator)
                        .param("y", p.hasNonNull("releaseYear") ? p.get("releaseYear").asInt() : null)
                        .param("d", p.hasNonNull("durationSeconds") ? p.get("durationSeconds").asInt() : null)
                        .param("mr", p.path("maturityRating").asString(null))
                        .param("pub", p.hasNonNull("publishedAt") ? Timestamp.from(Instant.parse(p.get("publishedAt").asString())) : null)
                        .update();
                    embedder.embed(docText(title, synopsis, genres)).ifPresent(em -> store(t, em));
                }
                case "ContentRemoved" -> jdbc.sql("delete from search_documents where title_id = :t").param("t", t).update();
                case "PlaybackStarted" -> jdbc.sql("update search_documents set popularity = popularity + 1 where title_id = :t").param("t", t).update();
                default -> { }
            }
        }

        void store(UUID t, Embedder.Embedding em) {
            jdbc.sql("update search_documents set embedding = cast(:v as vector), embedding_model = :m where title_id = :t")
                    .param("v", vectorLiteral(em.vector())).param("m", em.model()).param("t", t).update();
        }

        static String docText(String title, String synopsis, List<String> genres) {
            return title + ". " + String.join(", ", genres) + ". " + synopsis;
        }

        /** Backfills embeddings that failed (embedder down at publish time) or were made by the fallback embedder. */
        @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
        public void backfill() {
            record Row(UUID titleId, String title, String synopsis, String[] genres) {}
            List<Row> rows = jdbc.sql("""
                    select title_id, title, synopsis, genres from search_documents
                    where embedding is null or embedding_model like 'hashing%' limit 20""")
                .query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), (String[]) rs.getArray(4).getArray())).list();
            for (Row r : rows) {
                embedder.embed(docText(r.title(), r.synopsis() == null ? "" : r.synopsis(), List.of(r.genres())))
                        .filter(em -> !em.model().startsWith("hashing") || r.titleId() != null)
                        .ifPresent(em -> store(r.titleId(), em));
            }
        }
    }
}
