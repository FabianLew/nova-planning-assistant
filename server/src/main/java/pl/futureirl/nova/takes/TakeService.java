package pl.futureirl.nova.takes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.futureirl.nova.common.ApiException;
import pl.futureirl.nova.common.UserTime;
import pl.futureirl.nova.events.EventLog;

/**
 * Take log for editing (spec p.7). Recording is independent of the conversation: a take has its own id and
 * zero point, "Pauza zapisu" closes a segment (the conversation may continue but nothing is logged), and every
 * event stores its UTC time and offset from the take start.
 */
@Service
public class TakeService {
    /** Types the client may log while recording. pause/resume/stop/privacy come only from their endpoints. */
    static final Set<String> CLIENT_TYPES = Set.of("sync", "utterance", "interruption", "decision", "tool", "marker", "gap",
            "connection_lost", "connection_restored");
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final JdbcClient db;
    private final ObjectMapper json;
    private final UserTime time;
    private final EventLog events;

    public TakeService(JdbcClient db, ObjectMapper json, UserTime time, EventLog events) {
        this.db = db;
        this.json = json;
        this.time = time;
        this.events = events;
    }

    public record Take(UUID id, UUID sessionId, LocalDate date, String label, int number, String status, Instant startedAt,
                       Instant endedAt) {}

    public record TakeEvent(UUID id, UUID takeId, int seq, String type, String speaker, String text, Instant occurredAt,
                            long offsetMs, boolean isPrivate, Map<String, Object> payload) {}

    public record TakeDetail(Take take, List<TakeEvent> events) {}

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public Take start(UUID ownerId, UUID sessionId, String label) {
        if (sessionId != null) {
            var owned = db.sql("SELECT count(*) FROM sessions WHERE id = ? AND owner_id = ?").params(sessionId, ownerId).query(Integer.class).single();
            if (owned == 0) throw ApiException.notFound("session");
        }
        open(ownerId).ifPresent(t -> close(ownerId, t, "new_take"));
        var now = now();
        var date = LocalDate.ofInstant(now, time.zone(ownerId));
        var number = db.sql("SELECT coalesce(max(number), 0) + 1 FROM takes WHERE owner_id = ? AND take_date = ?")
                .params(ownerId, date).query(Integer.class).single();
        var id = UUID.randomUUID();
        db.sql("INSERT INTO takes (id, owner_id, session_id, take_date, label, number, started_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, ownerId, sessionId, date, label, number, ts(now)).update();
        insert(ownerId, id, now, now, "sync", null, "Punkt zerowy ujęcia " + number + ". Klaśnij lub powiedz znacznik.", Map.of(), null);
        events.publish(ownerId, "take.changed", Map.of("takeId", id.toString(), "status", "recording"));
        return get(ownerId, id);
    }

    @Transactional
    public Take pause(UUID ownerId, UUID takeId, boolean privacy) {
        var t = lock(ownerId, takeId);
        if (!"recording".equals(t.status())) throw ApiException.conflict("take_not_recording", "Take is " + t.status());
        var now = now();
        insert(ownerId, takeId, t.startedAt(), now, privacy ? "privacy" : "pause", null,
                privacy ? "Prywatność: zapis i mikrofon wstrzymane" : "Pauza zapisu", Map.of(), null);
        setStatus(takeId, "paused", null);
        events.publish(ownerId, "take.changed", Map.of("takeId", takeId.toString(), "status", "paused"));
        return get(ownerId, takeId);
    }

    @Transactional
    public Take resume(UUID ownerId, UUID takeId) {
        var t = lock(ownerId, takeId);
        if (!"paused".equals(t.status())) throw ApiException.conflict("take_not_paused", "Take is " + t.status());
        insert(ownerId, takeId, t.startedAt(), now(), "resume", null, "Wznowienie zapisu (nowy segment)", Map.of(), null);
        setStatus(takeId, "recording", null);
        events.publish(ownerId, "take.changed", Map.of("takeId", takeId.toString(), "status", "recording"));
        return get(ownerId, takeId);
    }

    @Transactional
    public Take stop(UUID ownerId, UUID takeId) {
        var t = lock(ownerId, takeId);
        if (!"closed".equals(t.status())) close(ownerId, t, "user");
        return get(ownerId, takeId);
    }

    private void close(UUID ownerId, Take t, String reason) {
        var now = now();
        insert(ownerId, t.id(), t.startedAt(), now, "stop", null, "Koniec ujęcia", Map.of("reason", reason), null);
        setStatus(t.id(), "closed", now);
        events.publish(ownerId, "take.changed", Map.of("takeId", t.id().toString(), "status", "closed"));
    }

    // ------------------------------------------------------------------ events

    /**
     * Logs one event into a recording take. Idempotent on {@code clientEventId}. Rejected while paused: during
     * a pause the conversation may go on, but nothing reaches the record.
     */
    @Transactional
    public TakeEvent log(UUID ownerId, UUID takeId, String clientEventId, String type, String speaker, String text,
                         Instant occurredAt, Map<String, Object> payload) {
        if (!CLIENT_TYPES.contains(type)) throw new IllegalArgumentException("unsupported event type " + type);
        var t = lock(ownerId, takeId);
        if (clientEventId != null) {
            var existing = byClientId(takeId, clientEventId);
            if (existing.isPresent()) return existing.get();
        }
        if (!"recording".equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "take_" + t.status(), "Take is " + t.status() + ", event not recorded");
        }
        var now = now();
        var at = occurredAt == null ? now : occurredAt.truncatedTo(ChronoUnit.MILLIS);
        if (at.isBefore(t.startedAt().minus(CLOCK_SKEW)) || at.isAfter(now.plus(CLOCK_SKEW))) {
            at = now; // a phone clock far off: fall back to server time rather than misplace the event
        }
        if (at.isBefore(t.startedAt())) at = t.startedAt();
        return insert(ownerId, takeId, t.startedAt(), at, type, speaker, text, payload == null ? Map.of() : payload, clientEventId);
    }

    @Transactional
    public TakeEvent edit(UUID ownerId, UUID takeId, UUID eventId, String text, Boolean isPrivate) {
        lock(ownerId, takeId);
        int n = db.sql("UPDATE take_events SET text = coalesce(?, text), private = coalesce(?, private) WHERE id = ? AND take_id = ? AND owner_id = ?")
                .params(text, isPrivate, eventId, takeId, ownerId).update();
        if (n == 0) throw ApiException.notFound("event");
        events.publish(ownerId, "take.changed", Map.of("takeId", takeId.toString(), "eventId", eventId.toString()));
        return db.sql("SELECT * FROM take_events WHERE id = ?").param(eventId).query(this::event).single();
    }

    // ------------------------------------------------------------------ reads and export

    public Optional<Take> open(UUID ownerId) {
        return db.sql("SELECT * FROM takes WHERE owner_id = ? AND status IN ('recording', 'paused')").param(ownerId)
                .query(this::take).optional();
    }

    public Take get(UUID ownerId, UUID takeId) {
        return db.sql("SELECT * FROM takes WHERE id = ? AND owner_id = ?").params(takeId, ownerId).query(this::take)
                .optional().orElseThrow(() -> ApiException.notFound("take"));
    }

    public List<Take> forDate(UUID ownerId, LocalDate date) {
        return db.sql("SELECT * FROM takes WHERE owner_id = ? AND take_date = ? ORDER BY number").params(ownerId, date)
                .query(this::take).list();
    }

    public TakeDetail detail(UUID ownerId, UUID takeId) {
        var t = get(ownerId, takeId);
        return new TakeDetail(t, db.sql("SELECT * FROM take_events WHERE take_id = ? ORDER BY seq").param(takeId).query(this::event).list());
    }

    /** Takes of a day with private events left out, sorted by take and time; pauses stay in as gaps. */
    public List<TakeDetail> exportable(UUID ownerId, LocalDate date, List<UUID> only) {
        return forDate(ownerId, date).stream()
                .filter(t -> only == null || only.isEmpty() || only.contains(t.id()))
                .map(t -> new TakeDetail(t, db.sql("SELECT * FROM take_events WHERE take_id = ? AND NOT private ORDER BY seq")
                        .param(t.id()).query(this::event).list()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private TakeEvent insert(UUID ownerId, UUID takeId, Instant takeStart, Instant at, String type, String speaker, String text,
                             Map<String, Object> payload, String clientEventId) {
        var offset = Math.max(0, Duration.between(takeStart, at).toMillis());
        return db.sql("""
                INSERT INTO take_events (id, owner_id, take_id, seq, type, speaker, text, occurred_at, offset_ms, payload, client_event_id)
                VALUES (?, ?, ?, (SELECT coalesce(max(seq), 0) + 1 FROM take_events WHERE take_id = ?), ?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING *
                """)
                .params(UUID.randomUUID(), ownerId, takeId, takeId, type, speaker, text, ts(at), offset, write(payload), clientEventId)
                .query(this::event).single();
    }

    private Optional<TakeEvent> byClientId(UUID takeId, String clientEventId) {
        return db.sql("SELECT * FROM take_events WHERE take_id = ? AND client_event_id = ?").params(takeId, clientEventId)
                .query(this::event).optional();
    }

    private Take lock(UUID ownerId, UUID takeId) {
        return db.sql("SELECT * FROM takes WHERE id = ? AND owner_id = ? FOR UPDATE").params(takeId, ownerId).query(this::take)
                .optional().orElseThrow(() -> ApiException.notFound("take"));
    }

    private void setStatus(UUID takeId, String status, Instant endedAt) {
        db.sql("UPDATE takes SET status = ?, ended_at = coalesce(?, ended_at) WHERE id = ?")
                .params(status, endedAt == null ? null : ts(endedAt), takeId).update();
    }

    private Take take(ResultSet rs, int i) throws SQLException {
        var ended = rs.getObject("ended_at", OffsetDateTime.class);
        return new Take(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class), rs.getObject("take_date", LocalDate.class),
                rs.getString("label"), rs.getInt("number"), rs.getString("status"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(), ended == null ? null : ended.toInstant());
    }

    @SuppressWarnings("unchecked")
    private TakeEvent event(ResultSet rs, int i) throws SQLException {
        try {
            return new TakeEvent(rs.getObject("id", UUID.class), rs.getObject("take_id", UUID.class), rs.getInt("seq"),
                    rs.getString("type"), rs.getString("speaker"), rs.getString("text"),
                    rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getLong("offset_ms"),
                    rs.getBoolean("private"), json.readValue(rs.getString("payload"), Map.class));
        } catch (JsonProcessingException e) {
            throw new SQLException(e);
        }
    }

    /** Millisecond precision everywhere, so stored times and offsets agree exactly. */
    private Instant now() {
        return time.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
