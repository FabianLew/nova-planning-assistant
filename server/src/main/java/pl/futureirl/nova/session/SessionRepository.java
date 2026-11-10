package pl.futureirl.nova.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All queries are scoped by owner_id (spec p.8: check the owner on every read). */
@Repository("voiceSessionRepository")
public class SessionRepository {
    private final JdbcClient db;
    private final ObjectMapper json;

    public SessionRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record SessionRow(UUID id, String provider, String model, String voice, String providerCallId, String status,
                             Instant startedAt, Instant connectedAt, Instant endedAt, String endReason) {}

    public record MessageRow(UUID id, int seq, String role, String content, boolean interrupted, String providerItemId,
                             Instant createdAt) {}

    public record EventRow(long id, int seq, String type, Map<String, Object> payload, Instant occurredAt) {}

    public void insert(UUID id, UUID ownerId, String provider, String model, String voice) {
        db.sql("INSERT INTO sessions (id, owner_id, provider, model, voice, status) VALUES (?, ?, ?, ?, ?, 'starting')")
                .params(id, ownerId, provider, model, voice).update();
    }

    public int countStartedSince(UUID ownerId, Instant since) {
        return db.sql("SELECT count(*) FROM sessions WHERE owner_id = ? AND started_at >= ?")
                .params(ownerId, OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC)).query(Integer.class).single();
    }

    /** Ends any session of this owner that is still open (only one live conversation at a time). */
    public int endOpen(UUID ownerId, String reason) {
        return db.sql("""
                UPDATE sessions SET status = 'ended', ended_at = now(), end_reason = ?
                WHERE owner_id = ? AND status IN ('starting', 'active')
                """).params(reason, ownerId).update();
    }

    public void markFailed(UUID id, String reason) {
        db.sql("UPDATE sessions SET status = 'failed', ended_at = now(), end_reason = ? WHERE id = ?")
                .params(reason, id).update();
    }

    public boolean markConnected(UUID id, UUID ownerId, String providerCallId) {
        return db.sql("""
                UPDATE sessions SET status = 'active', connected_at = coalesce(connected_at, now()),
                       provider_call_id = coalesce(?, provider_call_id)
                WHERE id = ? AND owner_id = ? AND status IN ('starting', 'active')
                """).params(providerCallId, id, ownerId).update() == 1;
    }

    public boolean markEnded(UUID id, UUID ownerId, String reason) {
        return db.sql("""
                UPDATE sessions SET status = 'ended', ended_at = now(), end_reason = ?
                WHERE id = ? AND owner_id = ? AND status IN ('starting', 'active')
                """).params(reason, id, ownerId).update() == 1;
    }

    public Optional<SessionRow> find(UUID id, UUID ownerId) {
        return db.sql("SELECT * FROM sessions WHERE id = ? AND owner_id = ?").params(id, ownerId)
                .query(this::session).optional();
    }

    /** Locks the session row so seq numbers are assigned one at a time. Call inside a transaction. */
    public Optional<SessionRow> lock(UUID id, UUID ownerId) {
        return db.sql("SELECT * FROM sessions WHERE id = ? AND owner_id = ? FOR UPDATE").params(id, ownerId)
                .query(this::session).optional();
    }

    public List<SessionRow> recent(UUID ownerId, int limit) {
        return db.sql("SELECT * FROM sessions WHERE owner_id = ? ORDER BY started_at DESC LIMIT ?")
                .params(ownerId, limit).query(this::session).list();
    }

    public Optional<MessageRow> findMessage(UUID sessionId, String providerItemId) {
        return db.sql("SELECT * FROM messages WHERE session_id = ? AND provider_item_id = ?")
                .params(sessionId, providerItemId).query(this::message).optional();
    }

    public MessageRow insertMessage(UUID sessionId, UUID ownerId, String role, String content, boolean interrupted,
                                    String providerItemId) {
        return db.sql("""
                INSERT INTO messages (id, session_id, owner_id, seq, role, content, interrupted, provider_item_id)
                VALUES (?, ?, ?, (SELECT coalesce(max(seq), 0) + 1 FROM messages WHERE session_id = ?), ?, ?, ?, ?)
                RETURNING *
                """)
                .params(UUID.randomUUID(), sessionId, ownerId, sessionId, role, content, interrupted, providerItemId)
                .query(this::message).single();
    }

    public List<MessageRow> messages(UUID sessionId, UUID ownerId) {
        return db.sql("SELECT * FROM messages WHERE session_id = ? AND owner_id = ? ORDER BY seq")
                .params(sessionId, ownerId).query(this::message).list();
    }

    public EventRow insertEvent(UUID sessionId, UUID ownerId, String type, Map<String, Object> payload) {
        return db.sql("""
                INSERT INTO events (session_id, owner_id, seq, type, payload)
                VALUES (?, ?, (SELECT coalesce(max(seq), 0) + 1 FROM events WHERE session_id = ?), ?, ?::jsonb)
                RETURNING *
                """)
                .params(sessionId, ownerId, sessionId, type, toJson(payload))
                .query(this::event).single();
    }

    public List<EventRow> events(UUID sessionId, UUID ownerId) {
        return db.sql("SELECT * FROM events WHERE session_id = ? AND owner_id = ? ORDER BY seq")
                .params(sessionId, ownerId).query(this::event).list();
    }

    private SessionRow session(ResultSet rs, int i) throws SQLException {
        return new SessionRow(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("model"),
                rs.getString("voice"), rs.getString("provider_call_id"), rs.getString("status"),
                instant(rs, "started_at"), instant(rs, "connected_at"), instant(rs, "ended_at"), rs.getString("end_reason"));
    }

    private MessageRow message(ResultSet rs, int i) throws SQLException {
        return new MessageRow(rs.getObject("id", UUID.class), rs.getInt("seq"), rs.getString("role"),
                rs.getString("content"), rs.getBoolean("interrupted"), rs.getString("provider_item_id"),
                instant(rs, "created_at"));
    }

    @SuppressWarnings("unchecked")
    private EventRow event(ResultSet rs, int i) throws SQLException {
        try {
            return new EventRow(rs.getLong("id"), rs.getInt("seq"), rs.getString("type"),
                    json.readValue(rs.getString("payload"), Map.class), instant(rs, "occurred_at"));
        } catch (JsonProcessingException e) {
            throw new SQLException("bad event payload", e);
        }
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        var t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not serialisable");
        }
    }
}
