package pl.futureirl.nova.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pl.futureirl.nova.common.ApiException;
import pl.futureirl.nova.common.UserTime;
import pl.futureirl.nova.events.EventLog;
import pl.futureirl.nova.memory.MemoryService;
import pl.futureirl.nova.plan.PlanService;
import pl.futureirl.nova.session.SessionRepository;

/**
 * Executes model function calls (spec p.5). The model only names a function and arguments; this class checks
 * the schema, the owner and the session, runs the operation in a transaction and stores the result under the
 * call id, so a retried call returns the same answer instead of acting twice.
 */
@Service
public class ToolService {
    private static final Logger log = LoggerFactory.getLogger(ToolService.class);
    private static final String HHMM = "([01]\\d|2[0-3]):[0-5]\\d";

    private final JdbcClient db;
    private final ObjectMapper strict;
    private final ObjectMapper json;
    private final Validator validator;
    private final TransactionTemplate tx;
    private final SessionRepository sessions;
    private final PlanService plans;
    private final MemoryService memory;
    private final UserTime time;
    private final EventLog events;

    public ToolService(JdbcClient db, ObjectMapper json, Validator validator, TransactionTemplate tx,
                       SessionRepository sessions, PlanService plans, MemoryService memory, UserTime time, EventLog events) {
        this.db = db;
        this.json = json;
        this.strict = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.validator = validator;
        this.tx = tx;
        this.sessions = sessions;
        this.plans = plans;
        this.memory = memory;
        this.time = time;
        this.events = events;
    }

    // ---- argument shapes (validated before anything runs)

    record NoArgs() {}
    record DateArgs(String date) {}
    record CreateTaskArgs(@NotBlank @Size(max = 200) String title, String date, @Pattern(regexp = HHMM) String startTime,
                          @Min(1) @Max(1440) Integer durationMin) {}
    record PlanItem(@NotBlank @Size(max = 200) String title, @NotBlank @Pattern(regexp = HHMM) String startTime,
                    @NotNull @Min(5) @Max(720) Integer durationMin, @PositiveOrZero @Max(100000) BigDecimal costPln,
                    @NotBlank @Size(max = 300) String reason) {}
    record ProposePlanArgs(String date, @NotNull @Size(min = 1, max = 12) List<@Valid PlanItem> items) {}
    record UpdatePlanArgs(@NotBlank String planId, @NotNull Integer expectedVersion, @NotBlank String taskId,
                          @NotBlank @Pattern(regexp = HHMM) String startTime) {}
    record TaskStatusArgs(@NotBlank String taskId, @NotBlank @Pattern(regexp = "done|skipped|refused") String status,
                          @Size(max = 300) String reason) {}
    record BusyArgs(String date, @NotBlank @Pattern(regexp = HHMM) String startTime, @NotBlank @Pattern(regexp = HHMM) String endTime,
                    @NotBlank @Size(max = 120) String label) {}
    record SaveMemoryArgs(@NotBlank @Size(max = 500) String content, @NotNull Boolean explicit, @NotNull Boolean sensitive,
                          @Min(1) @Max(5) Integer importance) {}
    record SearchArgs(@NotBlank @Size(max = 200) String query) {}

    public record ToolOutcome(String callId, String name, String status, Map<String, Object> output, boolean replayed) {}

    public ToolOutcome execute(UUID ownerId, UUID sessionId, String callId, String name, String rawArgs) {
        var session = sessions.find(sessionId, ownerId).orElseThrow(() -> ApiException.notFound("session"));
        var previous = stored(sessionId, callId);
        if (previous.isPresent()) {
            return previous.get();
        }
        if (!"active".equals(session.status()) && !"starting".equals(session.status())) {
            throw ApiException.conflict("session_closed", "Session is closed");
        }
        String status;
        Map<String, Object> output;
        UUID approvalId = null;
        try {
            output = tx.execute(s -> run(ownerId, sessionId, callId, name, rawArgs == null || rawArgs.isBlank() ? "{}" : rawArgs));
            status = String.valueOf(output.getOrDefault("status", "ok"));
            if (!List.of("ok", "pending_approval", "skipped").contains(status)) status = "ok";
            if (output.get("approvalId") instanceof String a) approvalId = UUID.fromString(a);
        } catch (ApiException e) {
            status = "error";
            output = error(e.code(), e.getMessage());
        } catch (IllegalArgumentException | ConstraintViolationException | JsonArgsException e) {
            status = "error";
            output = error("invalid_arguments", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tool {} failed", name, e);
            status = "error";
            output = error("internal_error", "Operacja się nie udała");
        }
        return store(ownerId, sessionId, callId, name, rawArgs, status, output, approvalId);
    }

    private Map<String, Object> run(UUID ownerId, UUID sessionId, String callId, String name, String raw) {
        var zone = time.zone(ownerId);
        return switch (name) {
            case "get_current_context" -> {
                parse(raw, NoArgs.class);
                var today = time.today(ownerId);
                var out = new LinkedHashMap<String, Object>();
                out.put("now", UserTime.HH_MM.format(time.now().atZone(zone)));
                out.put("date", today.toString());
                out.put("weekday", today.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("pl-PL")));
                out.put("timeZone", zone.getId());
                out.putAll(dayJson(ownerId, today));
                out.put("pendingApprovals", plans.pending(ownerId).stream().map(a -> Map.of("approvalId", a.id().toString(), "summary", a.summary())).toList());
                yield out;
            }
            case "get_calendar_events" -> {
                var a = parse(raw, DateArgs.class);
                yield dayJson(ownerId, time.parseDate(a.date(), ownerId));
            }
            case "create_task" -> {
                var a = parse(raw, CreateTaskArgs.class);
                var date = time.parseDate(a.date(), ownerId);
                var t = plans.createTask(ownerId, date, a.title(), a.startTime(), a.durationMin() == null ? 30 : a.durationMin(),
                        "assistant", "call:" + sessionId + ":" + callId);
                yield Map.of("status", "ok", "taskId", t.id().toString(), "title", t.title(), "date", date.toString(),
                        "start", String.valueOf(UserTime.hhmm(t.startAt(), zone)));
            }
            case "propose_daily_plan" -> {
                var a = parse(raw, ProposePlanArgs.class);
                var items = a.items().stream().map(i -> new PlanService.ProposedItem(i.title(), i.startTime(), i.durationMin(),
                        i.costPln() == null ? 0 : i.costPln().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact(),
                        i.reason())).toList();
                yield plans.proposePlan(ownerId, sessionId, time.parseDate(a.date(), ownerId), items);
            }
            case "update_daily_plan" -> {
                var a = parse(raw, UpdatePlanArgs.class);
                yield plans.requestChange(ownerId, sessionId, uuid(a.planId(), "planId"), a.expectedVersion(),
                        uuid(a.taskId(), "taskId"), a.startTime());
            }
            case "set_task_status" -> {
                var a = parse(raw, TaskStatusArgs.class);
                var t = plans.setStatus(ownerId, uuid(a.taskId(), "taskId"), a.status(), a.reason());
                yield Map.of("status", "ok", "taskId", t.id().toString(), "taskStatus", t.status());
            }
            case "add_busy_block" -> {
                var a = parse(raw, BusyArgs.class);
                var b = plans.addBusy(ownerId, time.parseDate(a.date(), ownerId), a.startTime(), a.endTime(), a.label());
                yield Map.of("status", "ok", "busyId", b.id().toString());
            }
            case "save_memory" -> {
                var a = parse(raw, SaveMemoryArgs.class);
                if (a.sensitive()) {
                    // Spec p.5: sensitive information is not stored by default.
                    yield Map.of("status", "skipped", "reason", "Informacja wrażliwa, nie zapisuję jej domyślnie.");
                }
                var m = memory.create(ownerId, a.explicit() ? "fact" : "inference", a.content(), a.explicit(),
                        a.importance() == null ? 3 : a.importance(), sessionId, "Rozmowa " + time.today(ownerId));
                yield Map.of("status", "ok", "memoryId", m.id().toString(), "memoryStatus", m.status(),
                        "note", "pending".equals(m.status()) ? "Zapisane jako wniosek do potwierdzenia przez twórcę." : "Zapamiętane.");
            }
            case "search_memory" -> {
                var a = parse(raw, SearchArgs.class);
                yield Map.of("status", "ok", "memories", memory.search(ownerId, a.query(), 5).stream()
                        .map(m -> Map.of("id", m.id().toString(), "content", m.content(), "kind", m.kind())).toList());
            }
            default -> throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "unknown_tool", "Unknown function " + name);
        };
    }

    Map<String, Object> dayJson(UUID ownerId, LocalDate date) {
        var zone = time.zone(ownerId);
        var day = plans.day(ownerId, date);
        var out = new LinkedHashMap<String, Object>();
        out.put("date", date.toString());
        if (day.plan() != null) {
            out.put("planId", day.plan().id().toString());
            out.put("planVersion", day.plan().version());
            out.put("planStatus", day.plan().status());
        } else {
            out.put("planId", null);
            out.put("planVersion", 0);
        }
        out.put("tasks", day.tasks().stream().map(t -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("taskId", t.id().toString());
            m.put("title", t.title());
            m.put("start", UserTime.hhmm(t.startAt(), zone));
            m.put("durationMin", t.durationMin());
            m.put("status", t.status());
            if (t.costGrosze() > 0) m.put("cost", PlanService.pln(t.costGrosze()));
            return m;
        }).toList());
        out.put("busy", day.busy().stream().map(b -> Map.of("label", b.label(), "from", UserTime.hhmm(b.startsAt(), zone),
                "to", UserTime.hhmm(b.endsAt(), zone))).toList());
        return out;
    }

    private <T> T parse(String raw, Class<T> type) {
        T value;
        try {
            value = strict.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new JsonArgsException("arguments do not match the function schema: " + e.getOriginalMessage());
        }
        var violations = validator.validate(value);
        if (!violations.isEmpty()) {
            var v = violations.iterator().next();
            throw new JsonArgsException(v.getPropertyPath() + ": " + v.getMessage());
        }
        return value;
    }

    private static UUID uuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new JsonArgsException(field + " is not a valid id");
        }
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("status", "error", "error", code, "message", message == null ? code : message);
    }

    private Optional<ToolOutcome> stored(UUID sessionId, String callId) {
        return db.sql("SELECT call_id, name, status, result FROM tool_executions WHERE session_id = ? AND call_id = ?")
                .params(sessionId, callId)
                .query((rs, i) -> new ToolOutcome(rs.getString(1), rs.getString(2), rs.getString(3), read(rs.getString(4)), true))
                .optional();
    }

    private ToolOutcome store(UUID ownerId, UUID sessionId, String callId, String name, String rawArgs, String status,
                              Map<String, Object> output, UUID approvalId) {
        int n = db.sql("""
                INSERT INTO tool_executions (id, owner_id, session_id, call_id, name, args, status, result, approval_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?) ON CONFLICT (session_id, call_id) DO NOTHING
                """)
                .params(UUID.randomUUID(), ownerId, sessionId, callId, name, argsJson(rawArgs), status, write(output), approvalId)
                .update();
        if (n == 0) {
            return stored(sessionId, callId).orElseThrow(); // a concurrent retry won the race
        }
        events.publish(ownerId, "tool.executed", Map.of("sessionId", sessionId.toString(), "callId", callId, "name", name, "status", status));
        return new ToolOutcome(callId, name, status, output, false);
    }

    private String argsJson(String raw) {
        try {
            json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            return raw == null || raw.isBlank() ? "{}" : raw;
        } catch (JsonProcessingException e) {
            return write(Map.of("raw", raw));
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String s) {
        try {
            return json.readValue(s, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static class JsonArgsException extends RuntimeException {
        JsonArgsException(String m) {
            super(m);
        }
    }
}
