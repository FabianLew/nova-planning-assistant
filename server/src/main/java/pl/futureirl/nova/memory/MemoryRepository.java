package pl.futureirl.nova.memory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MemoryRepository {
    private final JdbcClient db;

    public MemoryRepository(JdbcClient db) {
        this.db = db;
    }

    public Memory insert(UUID ownerId, String kind, String content, String status, int importance, UUID sessionId, String note) {
        return db.sql("""
                INSERT INTO memories (id, owner_id, kind, content, status, importance, source_session_id, source_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                """)
                .params(UUID.randomUUID(), ownerId, kind, content, status, importance, sessionId, note)
                .query(this::map).single();
    }

    public Optional<Memory> find(UUID id, UUID ownerId) {
        return db.sql("SELECT * FROM memories WHERE id = ? AND owner_id = ? AND status <> 'deleted'")
                .params(id, ownerId).query(this::map).optional();
    }

    public Optional<Memory> lock(UUID id, UUID ownerId) {
        return db.sql("SELECT * FROM memories WHERE id = ? AND owner_id = ? AND status <> 'deleted' FOR UPDATE")
                .params(id, ownerId).query(this::map).optional();
    }

    public List<Memory> list(UUID ownerId, String status) {
        if (status == null) {
            return db.sql("SELECT * FROM memories WHERE owner_id = ? AND status IN ('confirmed', 'pending') ORDER BY created_at DESC")
                    .param(ownerId).query(this::map).list();
        }
        return db.sql("SELECT * FROM memories WHERE owner_id = ? AND status = ? ORDER BY created_at DESC")
                .params(ownerId, status).query(this::map).list();
    }

    public Optional<Memory> findSameContent(UUID ownerId, String content) {
        return db.sql("SELECT * FROM memories WHERE owner_id = ? AND lower(content) = lower(?) AND status IN ('confirmed', 'pending') LIMIT 1")
                .params(ownerId, content).query(this::map).optional();
    }

    public Memory update(UUID id, String content, Integer importance, String status) {
        return db.sql("""
                UPDATE memories SET content = coalesce(?, content), importance = coalesce(?, importance),
                       status = coalesce(?, status), version = version + 1, updated_at = now()
                WHERE id = ? RETURNING *
                """)
                .params(content, importance, status, id).query(this::map).single();
    }

    /** Removes the text for good; the row stays only so the audit trail can point at an id. */
    public void erase(UUID id) {
        db.sql("UPDATE memories SET content = '', status = 'deleted', source_note = NULL, version = version + 1, updated_at = now() WHERE id = ?")
                .param(id).update();
    }

    public void audit(UUID ownerId, UUID memoryId, String op, int version) {
        db.sql("INSERT INTO memory_audit (owner_id, memory_id, operation, version) VALUES (?, ?, ?, ?)")
                .params(ownerId, memoryId, op, version).update();
    }

    /**
     * Up to {@code limit} confirmed memories ranked by text match, then importance and recency (spec p.4).
     * With a blank query the ranking falls back to importance and date.
     */
    public List<Memory> search(UUID ownerId, String query, int limit) {
        var tsq = query == null ? null : orQuery(query);
        if (tsq == null) {
            return db.sql("""
                    SELECT * FROM memories
                    WHERE owner_id = ? AND status = 'confirmed' AND kind <> 'episode'
                    ORDER BY importance DESC, updated_at DESC LIMIT ?
                    """).params(ownerId, limit).query(this::map).list();
        }
        // Matches first, then important facts even without a match, so "pracuję do 17" is never crowded out.
        return db.sql("""
                SELECT * FROM memories
                WHERE owner_id = ? AND status = 'confirmed' AND kind <> 'episode'
                  AND (search @@ to_tsquery('simple', ?) OR importance >= 4)
                ORDER BY ts_rank(search, to_tsquery('simple', ?)) DESC, importance DESC, updated_at DESC
                LIMIT ?
                """).params(ownerId, tsq, tsq, limit).query(this::map).list();
    }

    public List<Memory> recentEpisodes(UUID ownerId, int limit) {
        return db.sql("SELECT * FROM memories WHERE owner_id = ? AND kind = 'episode' AND status = 'confirmed' ORDER BY created_at DESC LIMIT ?")
                .params(ownerId, limit).query(this::map).list();
    }

    /** "spacer po montażu" -> "spacer:* | po:* | montażu:*" (OR of prefixes, punctuation stripped). */
    static String orQuery(String text) {
        var words = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().split("\\s+");
        var sb = new StringBuilder();
        for (var w : words) {
            if (w.length() < 3) continue;
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append(w).append(":*");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private Memory map(ResultSet rs, int i) throws SQLException {
        return new Memory(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("content"),
                rs.getString("status"), rs.getInt("importance"), rs.getObject("source_session_id", UUID.class),
                rs.getString("source_note"), rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
