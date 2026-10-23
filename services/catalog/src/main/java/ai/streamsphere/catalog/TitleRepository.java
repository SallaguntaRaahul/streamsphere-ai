package ai.streamsphere.catalog;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TitleRepository {
    private static final String COLS = """
            id, creator_id, creator_name, kind, title, synopsis, genres, language, maturity_rating, release_year,
            duration_seconds, editorial_state, media_state, media_asset_id, media_version, has_poster, rejection_reason,
            created_at, updated_at, published_at, version""";
    private final JdbcClient jdbc;

    public TitleRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    static Title map(ResultSet rs, int i) throws SQLException {
        Array g = rs.getArray("genres");
        return new Title(rs.getObject("id", UUID.class), rs.getString("creator_id"), rs.getString("creator_name"),
                rs.getString("kind"), rs.getString("title"), rs.getString("synopsis"),
                g == null ? List.of() : Arrays.asList((String[]) g.getArray()), rs.getString("language"),
                rs.getString("maturity_rating"), (Integer) rs.getObject("release_year"), (Integer) rs.getObject("duration_seconds"),
                EditorialState.valueOf(rs.getString("editorial_state")), rs.getString("media_state"),
                rs.getObject("media_asset_id", UUID.class), (Integer) rs.getObject("media_version"), rs.getBoolean("has_poster"),
                rs.getString("rejection_reason"), ts(rs, "created_at"), ts(rs, "updated_at"), ts(rs, "published_at"), rs.getInt("version"));
    }

    private static Instant ts(ResultSet rs, String c) throws SQLException {
        Timestamp t = rs.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    public void insert(Title t) {
        jdbc.sql("""
                insert into titles(id, creator_id, creator_name, kind, title, synopsis, genres, language, maturity_rating,
                  release_year, editorial_state, media_state, created_at, updated_at, version)
                values (:id, :creator, :creatorName, :kind, :title, :synopsis, :genres, :language, :rating, :year,
                  'DRAFT', 'NONE', now(), now(), 0)""")
            .param("id", t.id()).param("creator", t.creatorId()).param("creatorName", t.creatorName())
            .param("kind", t.kind()).param("title", t.title()).param("synopsis", t.synopsis())
            .param("genres", t.genres().toArray(String[]::new)).param("language", t.language())
            .param("rating", t.maturityRating()).param("year", t.releaseYear())
            .update();
    }

    public Optional<Title> find(UUID id) {
        return jdbc.sql("select " + COLS + " from titles where id = :id").param("id", id).query(TitleRepository::map).optional();
    }

    public Optional<Title> findForUpdate(UUID id) {
        return jdbc.sql("select " + COLS + " from titles where id = :id for update").param("id", id).query(TitleRepository::map).optional();
    }

    public List<Title> findPublishedByIds(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("select " + COLS + " from titles where id = any(:ids) and editorial_state = 'PUBLISHED'")
                .param("ids", ids.toArray(UUID[]::new)).query(TitleRepository::map).list();
    }

    /** Keyset pagination over (published_at desc, id desc). */
    public List<Title> published(String genre, Instant beforeAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLS + " from titles where editorial_state = 'PUBLISHED'");
        if (genre != null) sql.append(" and :genre = any(genres)");
        if (beforeAt != null) sql.append(" and (published_at, id) < (:at, :bid)");
        sql.append(" order by published_at desc, id desc limit :limit");
        var q = jdbc.sql(sql.toString()).param("limit", limit);
        if (genre != null) q = q.param("genre", genre);
        if (beforeAt != null) q = q.param("at", Timestamp.from(beforeAt)).param("bid", beforeId);
        return q.query(TitleRepository::map).list();
    }

    public List<Title> byCreator(String creatorId) {
        return jdbc.sql("select " + COLS + " from titles where creator_id = :c and editorial_state <> 'REMOVED' order by created_at desc")
                .param("c", creatorId).query(TitleRepository::map).list();
    }

    public List<Title> byState(EditorialState state, int limit) {
        return jdbc.sql("select " + COLS + " from titles where editorial_state = :s order by updated_at desc limit :l")
                .param("s", state.name()).param("l", limit).query(TitleRepository::map).list();
    }

    public List<GenreCount> genres() {
        return jdbc.sql("""
                select g as genre, count(*) as titles from titles, unnest(genres) g
                where editorial_state = 'PUBLISHED' group by g order by count(*) desc, g""")
            .query(GenreCount.class).list();
    }

    public record GenreCount(String genre, long titles) {}

    public void updateMetadata(Title t, int expectedVersion) {
        int n = jdbc.sql("""
                update titles set title = :title, synopsis = :synopsis, genres = :genres, language = :language,
                  maturity_rating = :rating, release_year = :year, updated_at = now(), version = version + 1
                where id = :id and version = :v""")
            .param("title", t.title()).param("synopsis", t.synopsis()).param("genres", t.genres().toArray(String[]::new))
            .param("language", t.language()).param("rating", t.maturityRating()).param("year", t.releaseYear())
            .param("id", t.id()).param("v", expectedVersion).update();
        if (n == 0) throw new OptimisticLockingFailureException("title " + t.id());
    }

    public void setEditorialState(UUID id, EditorialState s, String reason) {
        jdbc.sql("""
                update titles set editorial_state = :s, rejection_reason = :r, updated_at = now(), version = version + 1,
                  published_at = case when :s = 'PUBLISHED' then now() else published_at end
                where id = :id""")
            .param("s", s.name()).param("r", reason).param("id", id).update();
    }

    public void setMedia(UUID id, String mediaState, UUID assetId, Integer assetVersion, Integer duration, Boolean hasPoster) {
        jdbc.sql("""
                update titles set media_state = :ms, media_asset_id = coalesce(:aid, media_asset_id),
                  media_version = coalesce(:av, media_version), duration_seconds = coalesce(:d, duration_seconds),
                  has_poster = coalesce(:p, has_poster), updated_at = now(), version = version + 1
                where id = :id""")
            .param("ms", mediaState).param("aid", assetId).param("av", assetVersion).param("d", duration)
            .param("p", hasPoster).param("id", id).update();
    }

    public void audit(UUID titleId, String actor, String action, String detail) {
        jdbc.sql("insert into audit_events(id, title_id, actor, action, detail, at) values (:id, :t, :a, :ac, :d, now())")
            .param("id", UUID.randomUUID()).param("t", titleId).param("a", actor).param("ac", action).param("d", detail).update();
    }
}
