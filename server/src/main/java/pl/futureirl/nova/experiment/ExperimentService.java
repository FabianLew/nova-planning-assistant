package pl.futureirl.nova.experiment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.futureirl.nova.common.ApiException;
import pl.futureirl.nova.common.InsufficientBudgetException;
import pl.futureirl.nova.common.UserTime;
import pl.futureirl.nova.config.NovaProperties;
import pl.futureirl.nova.events.EventLog;
import pl.futureirl.nova.experiment.ExperimentModels.ApiUsage;
import pl.futureirl.nova.experiment.ExperimentModels.Budget;
import pl.futureirl.nova.experiment.ExperimentModels.DayReport;
import pl.futureirl.nova.experiment.ExperimentModels.Experiment;
import pl.futureirl.nova.experiment.ExperimentModels.Expense;
import pl.futureirl.nova.experiment.ExperimentModels.Refusal;
import pl.futureirl.nova.experiment.ExperimentModels.Report;
import pl.futureirl.nova.experiment.ExperimentModels.Review;
import pl.futureirl.nova.experiment.ExperimentModels.Status;

/**
 * The seven-day experiment (spec p.6). Budget rule: available = limit − expenses − open reservations.
 * Reservations are checked under a lock on the experiment row. A real expense above the limit is still
 * recorded and then blocks further paid proposals. Pause stops new proposals.
 */
@Service
public class ExperimentService {
    private static final String RUNNING = "('draft', 'active', 'paused')";

    private final JdbcClient db;
    private final ObjectMapper json;
    private final UserTime time;
    private final EventLog events;
    private final long apiCostPerMinute;

    public ExperimentService(JdbcClient db, ObjectMapper json, UserTime time, EventLog events, NovaProperties props) {
        this.db = db;
        this.json = json;
        this.time = time;
        this.events = events;
        this.apiCostPerMinute = props.limits().apiCostPerMinuteGrosze();
    }

    public record Config(String goal, LocalDate startDate, String workStart, String workEnd, List<String> exclusions,
                         long budgetGrosze, long apiLimitGrosze, String dayStart, Map<String, Object> baseline) {}

    // ------------------------------------------------------------------ lifecycle

    /** The running experiment, or the most recent finished one. Completes it once its last day has passed. */
    @Transactional
    public Optional<Experiment> current(UUID ownerId) {
        var running = db.sql("SELECT * FROM experiments WHERE owner_id = ? AND status IN " + RUNNING).param(ownerId)
                .query(this::map).optional();
        if (running.isPresent() && "active".equals(running.get().status())
                && experimentDate(ownerId, running.get()).isAfter(running.get().endDate())) {
            decide(running.get(), "completed", "Koniec " + running.get().days() + " dni");
            return find(ownerId, running.get().id());
        }
        if (running.isPresent()) return running;
        return db.sql("SELECT * FROM experiments WHERE owner_id = ? ORDER BY created_at DESC LIMIT 1").param(ownerId)
                .query(this::map).optional();
    }

    public Optional<Experiment> active(UUID ownerId) {
        return current(ownerId).filter(e -> "active".equals(e.status()) || "paused".equals(e.status()));
    }

    @Transactional
    public Experiment create(UUID ownerId, Config c) {
        validate(c);
        if (db.sql("SELECT count(*) FROM experiments WHERE owner_id = ? AND status IN " + RUNNING).param(ownerId)
                .query(Integer.class).single() > 0) {
            throw ApiException.conflict("experiment_exists", "Finish or end the current experiment first");
        }
        var id = UUID.randomUUID();
        db.sql("""
                INSERT INTO experiments (id, owner_id, goal, start_date, work_start, work_end, exclusions, budget_grosze,
                                         api_limit_grosze, day_start, baseline)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
                """)
                .params(id, ownerId, c.goal().strip(), c.startDate(), c.workStart(), c.workEnd(), write(c.exclusions()),
                        c.budgetGrosze(), c.apiLimitGrosze(), c.dayStart(), write(c.baseline())).update();
        events.publish(ownerId, "experiment.changed", Map.of("id", id.toString(), "status", "draft"));
        return find(ownerId, id).orElseThrow();
    }

    /** Rules can change only while the experiment is a draft; every edit is a new rules version. */
    @Transactional
    public Experiment update(UUID ownerId, UUID id, Config c) {
        validate(c);
        var e = lock(ownerId, id);
        if (!"draft".equals(e.status())) throw ApiException.conflict("rules_locked", "Rules are fixed once the experiment starts");
        db.sql("""
                UPDATE experiments SET goal = ?, start_date = ?, work_start = ?, work_end = ?, exclusions = ?::jsonb,
                       budget_grosze = ?, api_limit_grosze = ?, day_start = ?, baseline = ?::jsonb, rules_version = rules_version + 1
                WHERE id = ?
                """)
                .params(c.goal().strip(), c.startDate(), c.workStart(), c.workEnd(), write(c.exclusions()), c.budgetGrosze(),
                        c.apiLimitGrosze(), c.dayStart(), write(c.baseline()), id).update();
        events.publish(ownerId, "experiment.changed", Map.of("id", id.toString(), "status", "draft"));
        return find(ownerId, id).orElseThrow();
    }

    /** Draft → active, after the creator accepts the rules (spec p.6). */
    @Transactional
    public Experiment transition(UUID ownerId, UUID id, String action, String note) {
        var e = lock(ownerId, id);
        var next = switch (action) {
            case "activate" -> require(e, "draft", "active");
            case "pause" -> require(e, "active", "paused");
            case "resume" -> require(e, "paused", "active");
            case "end" -> {
                if (!List.of("active", "paused").contains(e.status())) throw ApiException.conflict("bad_state", "Experiment is " + e.status());
                yield "ended_early";
            }
            default -> throw new IllegalArgumentException("action must be activate, pause, resume or end");
        };
        decide(e, next, note);
        return find(ownerId, id).orElseThrow();
    }

    private void decide(Experiment e, String status, String note) {
        db.sql("""
                UPDATE experiments SET status = ?,
                       activated_at = CASE WHEN ? = 'active' THEN coalesce(activated_at, now()) ELSE activated_at END,
                       ended_at = CASE WHEN ? IN ('completed', 'ended_early') THEN now() ELSE ended_at END
                WHERE id = ?
                """).params(status, status, status, e.id()).update();
        db.sql("INSERT INTO experiment_decisions (experiment_id, decision, note, rules_version) VALUES (?, ?, ?, ?)")
                .params(e.id(), status, note, e.rulesVersion()).update();
        if (List.of("completed", "ended_early").contains(status)) {
            db.sql("UPDATE reservations SET status = 'released', closed_at = now() WHERE experiment_id = ? AND status = 'open'")
                    .param(e.id()).update();
        }
        var owner = db.sql("SELECT owner_id FROM experiments WHERE id = ?").param(e.id()).query(UUID.class).single();
        events.publish(owner, "experiment.changed", Map.of("id", e.id().toString(), "status", status));
    }

    private static String require(Experiment e, String from, String to) {
        if (!from.equals(e.status())) throw ApiException.conflict("bad_state", "Experiment is " + e.status());
        return to;
    }

    // ------------------------------------------------------------------ days

    /** The experiment's calendar day: before {@code dayStart} (e.g. 04:00) still counts as the previous day. */
    public LocalDate experimentDate(UUID ownerId, Experiment e) {
        var zone = time.zone(ownerId);
        var local = time.now().atZone(zone);
        var dayStart = LocalTime.parse(e.dayStart());
        return local.toLocalTime().isBefore(dayStart) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    public int dayIndex(UUID ownerId, Experiment e) {
        return (int) ChronoUnit.DAYS.between(e.startDate(), experimentDate(ownerId, e)) + 1;
    }

    // ------------------------------------------------------------------ budget

    public Budget budget(Experiment e) {
        var spent = db.sql("SELECT coalesce(sum(amount_grosze), 0) FROM expenses WHERE experiment_id = ?").param(e.id())
                .query(Long.class).single();
        var reserved = db.sql("SELECT coalesce(sum(amount_grosze), 0) FROM reservations WHERE experiment_id = ? AND status = 'open'")
                .param(e.id()).query(Long.class).single();
        var available = e.budgetGrosze() - spent - reserved;
        return new Budget(e.budgetGrosze(), spent, reserved, available, spent > e.budgetGrosze());
    }

    /**
     * Gate for any new proposal from the assistant: refused while paused, refused for excluded activities, and for
     * paid ones refused when the cost does not fit or the limit was already exceeded.
     */
    public void checkProposal(UUID ownerId, String title, long costGrosze) {
        var e = active(ownerId).orElse(null);
        if (e == null) return;
        if ("paused".equals(e.status())) {
            throw ApiException.conflict("experiment_paused", "Eksperyment jest wstrzymany, nie proponuj nowych aktywności");
        }
        for (var ex : e.exclusions()) {
            if (!ex.isBlank() && title.toLowerCase(Locale.ROOT).contains(ex.toLowerCase(Locale.ROOT).strip())) {
                throw ApiException.conflict("excluded_activity", "„" + ex.strip() + "” jest wykluczone w regułach eksperymentu");
            }
        }
        if (costGrosze > 0) {
            var b = budget(e);
            if (b.overLimit()) throw ApiException.conflict("budget_exceeded", "Limit przekroczony, płatne propozycje są zablokowane");
            if (costGrosze > b.availableGrosze()) {
                throw ApiException.conflict("insufficient_budget", "Dostępne " + pln(b.availableGrosze()) + ", koszt " + pln(costGrosze));
            }
        }
    }

    /** Reserves budget for an approved paid task. Must run in the caller's transaction (rolls back on failure). */
    public void reserve(UUID ownerId, UUID taskId, long amountGrosze) {
        if (amountGrosze <= 0) return;
        var e = active(ownerId).orElse(null);
        if (e == null) return;
        lock(ownerId, e.id()); // serialises concurrent reservations
        var b = budget(e);
        if (b.overLimit() || amountGrosze > b.availableGrosze()) {
            throw new InsufficientBudgetException("Brak środków: dostępne " + pln(b.availableGrosze()) + ", potrzeba " + pln(amountGrosze));
        }
        db.sql("INSERT INTO reservations (id, owner_id, experiment_id, task_id, amount_grosze) VALUES (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), ownerId, e.id(), taskId, amountGrosze).update();
        events.publish(ownerId, "budget.changed", Map.of("experimentId", e.id().toString()));
    }

    /** Cancelling before purchase frees the whole reservation. */
    public void release(UUID ownerId, UUID taskId) {
        int n = db.sql("UPDATE reservations SET status = 'released', closed_at = now() WHERE task_id = ? AND owner_id = ? AND status = 'open'")
                .params(taskId, ownerId).update();
        if (n > 0) events.publish(ownerId, "budget.changed", Map.of("taskId", taskId.toString()));
    }

    /**
     * Records a real expense the creator reports. If it belongs to a task with an open reservation, the
     * reservation turns into the expense and the difference becomes available again.
     */
    @Transactional
    public Expense recordExpense(UUID ownerId, long amountGrosze, String description, UUID taskId, String idempotencyKey) {
        if (amountGrosze <= 0) throw new IllegalArgumentException("amount must be positive");
        if (idempotencyKey != null) {
            var existing = expenseByKey(ownerId, idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }
        var e = active(ownerId).orElseThrow(() -> ApiException.conflict("no_experiment", "Brak aktywnego eksperymentu"));
        lock(ownerId, e.id());
        if (idempotencyKey != null) {
            var existing = expenseByKey(ownerId, idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }
        UUID reservationId = null;
        if (taskId != null) {
            var owned = db.sql("SELECT count(*) FROM tasks WHERE id = ? AND owner_id = ?").params(taskId, ownerId).query(Integer.class).single();
            if (owned == 0) throw ApiException.notFound("task");
            reservationId = db.sql("""
                    UPDATE reservations SET status = 'converted', closed_at = now()
                    WHERE task_id = ? AND status = 'open' RETURNING id
                    """).param(taskId).query(UUID.class).optional().orElse(null);
        }
        var id = UUID.randomUUID();
        var date = experimentDate(ownerId, e);
        db.sql("""
                INSERT INTO expenses (id, owner_id, experiment_id, task_id, reservation_id, amount_grosze, description, expense_date, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, ownerId, e.id(), taskId, reservationId, amountGrosze, description.strip(), date, idempotencyKey).update();
        var b = budget(e);
        events.publish(ownerId, "budget.changed", Map.of("experimentId", e.id().toString(), "overLimit", b.overLimit()));
        return new Expense(id, date, amountGrosze, description.strip(), taskId);
    }

    private Optional<Expense> expenseByKey(UUID ownerId, String key) {
        return db.sql("SELECT id, expense_date, amount_grosze, description, task_id FROM expenses WHERE owner_id = ? AND idempotency_key = ?")
                .params(ownerId, key)
                .query((rs, i) -> new Expense(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getLong(3),
                        rs.getString(4), rs.getObject(5, UUID.class)))
                .optional();
    }

    // ------------------------------------------------------------------ API spend (separate from the activity budget)

    public ApiUsage apiUsage(UUID ownerId, Experiment e) {
        var from = time.startOfDay(e.startDate(), time.zone(ownerId));
        var to = e.endedAt() != null ? e.endedAt() : time.now();
        var seconds = db.sql("""
                SELECT coalesce(sum(extract(epoch FROM (coalesce(ended_at, ?) - connected_at))), 0)
                FROM sessions WHERE owner_id = ? AND connected_at IS NOT NULL AND connected_at >= ? AND connected_at <= ?
                """).params(ts(time.now()), ownerId, ts(from), ts(to)).query(Double.class).single();
        var minutes = (long) Math.ceil(seconds / 60.0);
        var cost = minutes * apiCostPerMinute;
        return new ApiUsage(minutes, cost, e.apiLimitGrosze(), e.apiLimitGrosze() > 0 && cost >= e.apiLimitGrosze());
    }

    /** Refuses a new voice session once the experiment's API limit is spent. */
    public void checkApiLimit(UUID ownerId) {
        active(ownerId).ifPresent(e -> {
            var usage = apiUsage(ownerId, e);
            if (usage.overLimit()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "api_limit_reached",
                        "Limit kosztów API eksperymentu wyczerpany (" + pln(usage.estimatedGrosze()) + ")");
            }
        });
    }

    // ------------------------------------------------------------------ reviews

    @Transactional
    public Review review(UUID ownerId, LocalDate date, int rating, String comment) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("rating must be 1-5");
        var e = current(ownerId).orElseThrow(() -> ApiException.notFound("experiment"));
        if (date.isBefore(e.startDate()) || date.isAfter(e.endDate())) throw new IllegalArgumentException("date is outside the experiment");
        db.sql("""
                INSERT INTO daily_reviews (id, owner_id, experiment_id, review_date, rating, comment) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (experiment_id, review_date) DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment, updated_at = now()
                """).params(UUID.randomUUID(), ownerId, e.id(), date, rating, comment == null ? null : comment.strip()).update();
        events.publish(ownerId, "review.saved", Map.of("date", date.toString(), "rating", rating));
        return reviewFor(e.id(), date).orElseThrow();
    }

    Optional<Review> reviewFor(UUID experimentId, LocalDate date) {
        return db.sql("SELECT review_date, rating, comment, updated_at FROM daily_reviews WHERE experiment_id = ? AND review_date = ?")
                .params(experimentId, date)
                .query((rs, i) -> new Review(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant()))
                .optional();
    }

    // ------------------------------------------------------------------ status and report

    public Optional<Status> status(UUID ownerId) {
        return current(ownerId).map(e -> {
            var date = experimentDate(ownerId, e);
            var day = Math.max(0, Math.min(dayIndex(ownerId, e), e.days() + 1));
            var counts = db.sql("""
                    SELECT count(*) FILTER (WHERE t.status = 'done'),
                           count(*) FILTER (WHERE t.status IN ('planned', 'done', 'skipped', 'refused'))
                    FROM tasks t JOIN daily_plans p ON p.id = t.plan_id WHERE p.owner_id = ? AND p.plan_date = ?
                    """).params(ownerId, date).query((rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}).single();
            var refusals = db.sql("""
                    SELECT count(*) FROM tasks t JOIN daily_plans p ON p.id = t.plan_id
                    WHERE p.owner_id = ? AND t.status = 'refused' AND p.plan_date BETWEEN ? AND ?
                    """).params(ownerId, e.startDate(), e.endDate()).query(Integer.class).single();
            return new Status(e, day, date, budget(e), apiUsage(ownerId, e), refusals, counts[0], counts[1],
                    reviewFor(e.id(), date).orElse(null), reviewFor(e.id(), date.minusDays(1)).orElse(null));
        });
    }

    public Report report(UUID ownerId, UUID experimentId) {
        var e = find(ownerId, experimentId).orElseThrow(() -> ApiException.notFound("experiment"));
        var days = new ArrayList<DayReport>();
        int planned = 0, done = 0, refused = 0, skipped = 0;
        double ratingSum = 0;
        int rated = 0;
        for (int i = 0; i < e.days(); i++) {
            var date = e.startDate().plusDays(i);
            var c = db.sql("""
                    SELECT count(*) FILTER (WHERE t.status IN ('planned', 'done', 'skipped', 'refused')),
                           count(*) FILTER (WHERE t.status = 'done'),
                           count(*) FILTER (WHERE t.status = 'skipped'),
                           count(*) FILTER (WHERE t.status = 'refused'),
                           coalesce(sum(t.duration_min) FILTER (WHERE t.status IN ('planned', 'done', 'skipped', 'refused')), 0),
                           coalesce(sum(coalesce(t.actual_min, t.duration_min)) FILTER (WHERE t.status = 'done'), 0)
                    FROM tasks t JOIN daily_plans p ON p.id = t.plan_id WHERE p.owner_id = ? AND p.plan_date = ?
                    """).params(ownerId, date).query((rs, k) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)}).single();
            var spent = db.sql("SELECT coalesce(sum(amount_grosze), 0) FROM expenses WHERE experiment_id = ? AND expense_date = ?")
                    .params(e.id(), date).query(Long.class).single();
            var r = reviewFor(e.id(), date);
            days.add(new DayReport(i + 1, date, r.map(Review::rating).orElse(null), r.map(Review::comment).orElse(null),
                    (int) c[0], (int) c[1], (int) c[2], (int) c[3], spent, c[4], c[5]));
            planned += c[0];
            done += c[1];
            skipped += c[2];
            refused += c[3];
            if (r.isPresent()) {
                ratingSum += r.get().rating();
                rated++;
            }
        }
        var expenses = db.sql("SELECT id, expense_date, amount_grosze, description, task_id FROM expenses WHERE experiment_id = ? ORDER BY created_at")
                .param(e.id())
                .query((rs, i) -> new Expense(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getString(4), rs.getObject(5, UUID.class)))
                .list();
        var refusals = db.sql("""
                SELECT p.plan_date, t.title, t.refusal_reason FROM tasks t JOIN daily_plans p ON p.id = t.plan_id
                WHERE p.owner_id = ? AND t.status = 'refused' AND p.plan_date BETWEEN ? AND ? ORDER BY p.plan_date, t.start_at
                """).params(ownerId, e.startDate(), e.endDate())
                .query((rs, i) -> new Refusal(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3))).list();
        return new Report(e, days, rated == 0 ? null : Math.round(ratingSum / rated * 100) / 100.0, planned, done, refused,
                skipped, budget(e), expenses, refusals, apiUsage(ownerId, e), e.baseline());
    }

    // ------------------------------------------------------------------ helpers

    public Optional<Experiment> find(UUID ownerId, UUID id) {
        return db.sql("SELECT * FROM experiments WHERE id = ? AND owner_id = ?").params(id, ownerId).query(this::map).optional();
    }

    private Experiment lock(UUID ownerId, UUID id) {
        return db.sql("SELECT * FROM experiments WHERE id = ? AND owner_id = ? FOR UPDATE").params(id, ownerId)
                .query(this::map).optional().orElseThrow(() -> ApiException.notFound("experiment"));
    }

    private static void validate(Config c) {
        if (c.goal() == null || c.goal().isBlank()) throw new IllegalArgumentException("goal is required");
        if (c.budgetGrosze() < 0 || c.apiLimitGrosze() < 0) throw new IllegalArgumentException("amounts must not be negative");
        for (var t : List.of(c.workStart(), c.workEnd(), c.dayStart())) {
            try {
                LocalTime.parse(t);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("times must be HH:mm");
            }
        }
    }

    private Experiment map(ResultSet rs, int i) throws SQLException {
        try {
            return new Experiment(rs.getObject("id", UUID.class), rs.getString("goal"), rs.getObject("start_date", LocalDate.class),
                    rs.getInt("days"), rs.getString("work_start"), rs.getString("work_end"),
                    json.readValue(rs.getString("exclusions"), new TypeReference<List<String>>() {}),
                    rs.getLong("budget_grosze"), rs.getLong("api_limit_grosze"), rs.getString("day_start"),
                    json.readValue(rs.getString("baseline"), new TypeReference<Map<String, Object>>() {}),
                    rs.getInt("rules_version"), rs.getString("status"), instant(rs, "activated_at"), instant(rs, "ended_at"));
        } catch (JsonProcessingException e) {
            throw new SQLException(e);
        }
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        var t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o == null ? Map.of() : o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String pln(long grosze) {
        return String.format(Locale.forLanguageTag("pl-PL"), "%.2f zł", grosze / 100.0);
    }

}
