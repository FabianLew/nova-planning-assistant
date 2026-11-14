package pl.futureirl.nova.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable, ordered change feed per owner (plan, tasks, approvals, memory). Rows are written in the same
 * transaction as the change; live SSE clients are notified only after commit.
 */
@Component
public class EventLog {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final SseHub hub;

    public EventLog(JdbcClient db, ObjectMapper json, SseHub hub) {
        this.db = db;
        this.json = json;
        this.hub = hub;
    }

    public record OwnerEvent(long seq, String type, Map<String, Object> payload, Instant occurredAt) {}

    public void publish(UUID ownerId, String type, Map<String, Object> payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        var event = db.sql("INSERT INTO owner_events (owner_id, type, payload) VALUES (?, ?, ?::jsonb) RETURNING seq, occurred_at")
                .params(ownerId, type, body)
                .query((rs, i) -> new OwnerEvent(rs.getLong(1), type, payload, rs.getObject(2, OffsetDateTime.class).toInstant()))
                .single();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    hub.push(ownerId, event);
                }
            });
        } else {
            hub.push(ownerId, event);
        }
    }

    @SuppressWarnings("unchecked")
    public List<OwnerEvent> since(UUID ownerId, long after, int limit) {
        return db.sql("SELECT seq, type, payload, occurred_at FROM owner_events WHERE owner_id = ? AND seq > ? ORDER BY seq LIMIT ?")
                .params(ownerId, after, limit)
                .query((rs, i) -> {
                    try {
                        return new OwnerEvent(rs.getLong(1), rs.getString(2), json.readValue(rs.getString(3), Map.class),
                                rs.getObject(4, OffsetDateTime.class).toInstant());
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .list();
    }

    public long lastSeq(UUID ownerId) {
        return db.sql("SELECT coalesce(max(seq), 0) FROM owner_events WHERE owner_id = ?").param(ownerId)
                .query(Long.class).single();
    }
}
