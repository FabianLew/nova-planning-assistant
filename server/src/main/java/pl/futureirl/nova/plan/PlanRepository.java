package pl.futureirl.nova.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pl.futureirl.nova.plan.PlanModels.Approval;
import pl.futureirl.nova.plan.PlanModels.BusyBlock;
import pl.futureirl.nova.plan.PlanModels.Plan;
import pl.futureirl.nova.plan.PlanModels.Task;

@Repository
public class PlanRepository {
    private final JdbcClient db;
    private final ObjectMapper json;

    public PlanRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    // ---- plans

    /** Returns the plan for the day, creating an empty draft the first time. Locks it (call in a transaction). */
    public Plan lockOrCreate(UUID ownerId, LocalDate date) {
        db.sql("INSERT INTO daily_plans (id, owner_id, plan_date) VALUES (?, ?, ?) ON CONFLICT (owner_id, plan_date) DO NOTHING")
                .params(UUID.randomUUID(), ownerId, date).update();
        return db.sql("SELECT * FROM daily_plans WHERE owner_id = ? AND plan_date = ? FOR UPDATE")
                .params(ownerId, date).query(this::plan).single();
    }

    public Optional<Plan> find(UUID ownerId, LocalDate date) {
        return db.sql("SELECT * FROM daily_plans WHERE owner_id = ? AND plan_date = ?").params(ownerId, date)
                .query(this::plan).optional();
    }

    public Optional<Plan> lock(UUID planId, UUID ownerId) {
        return db.sql("SELECT * FROM daily_plans WHERE id = ? AND owner_id = ? FOR UPDATE").params(planId, ownerId)
                .query(this::plan).optional();
    }

    public Plan bump(UUID planId, String status) {
        return db.sql("UPDATE daily_plans SET version = version + 1, status = coalesce(?, status), updated_at = now() WHERE id = ? RETURNING *")
                .params(status, planId).query(this::plan).single();
    }

    public void recordChange(UUID ownerId, UUID planId, int from, int to, String cause, Map<String, Object> diff) {
        db.sql("INSERT INTO plan_changes (owner_id, plan_id, from_version, to_version, cause, diff) VALUES (?, ?, ?, ?, ?, ?::jsonb)")
                .params(ownerId, planId, from, to, cause, toJson(diff)).update();
    }

    // ---- tasks

    public Task insertTask(UUID ownerId, UUID planId, UUID proposalId, String title, Instant startAt, int durationMin,
                           long costGrosze, String reason, String status, String source, String idempotencyKey) {
        return db.sql("""
                INSERT INTO tasks (id, owner_id, plan_id, proposal_id, title, start_at, duration_min, cost_grosze, reason,
                                   status, source, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                """)
                .params(UUID.randomUUID(), ownerId, planId, proposalId, title, ts(startAt), durationMin, costGrosze, reason,
                        status, source, idempotencyKey)
                .query(this::task).single();
    }

    public Optional<Task> taskByKey(UUID ownerId, String key) {
        return db.sql("SELECT * FROM tasks WHERE owner_id = ? AND idempotency_key = ?").params(ownerId, key)
                .query(this::task).optional();
    }

    public Optional<Task> task(UUID taskId, UUID ownerId) {
        return db.sql("SELECT * FROM tasks WHERE id = ? AND owner_id = ?").params(taskId, ownerId).query(this::task).optional();
    }

    public List<Task> tasks(UUID planId) {
        return db.sql("SELECT * FROM tasks WHERE plan_id = ? AND status <> 'cancelled' ORDER BY start_at NULLS LAST, created_at")
                .param(planId).query(this::task).list();
    }

    public Task updateTask(UUID taskId, String title, Instant startAt, Integer durationMin, String status, String refusalReason) {
        return db.sql("""
                UPDATE tasks SET title = coalesce(?, title), start_at = coalesce(?, start_at),
                       duration_min = coalesce(?, duration_min), status = coalesce(?, status),
                       refusal_reason = coalesce(?, refusal_reason), updated_at = now()
                WHERE id = ? RETURNING *
                """)
                .params(title, ts(startAt), durationMin, status, refusalReason, taskId).query(this::task).single();
    }

    public Task setActualMinutes(UUID taskId, int minutes) {
        return db.sql("UPDATE tasks SET actual_min = ?, updated_at = now() WHERE id = ? RETURNING *").params(minutes, taskId)
                .query(this::task).single();
    }

    public int setProposalTasks(UUID proposalId, String status) {
        return db.sql("UPDATE tasks SET status = ?, updated_at = now() WHERE proposal_id = ? AND status = 'proposed'")
                .params(status, proposalId).update();
    }

    /** Accepting a new plan replaces the assistant's earlier, not yet started items; the creator's own tasks stay. */
    public List<UUID> cancelSupersededAssistantTasks(UUID planId, UUID keepProposalId) {
        return db.sql("""
                UPDATE tasks SET status = 'cancelled', updated_at = now()
                WHERE plan_id = ? AND source = 'assistant' AND status = 'planned'
                  AND (proposal_id IS NULL OR proposal_id <> ?)
                RETURNING id
                """).params(planId, keepProposalId).query(UUID.class).list();
    }

    // ---- busy blocks

    public BusyBlock insertBusy(UUID ownerId, Instant start, Instant end, String label) {
        return db.sql("INSERT INTO busy_blocks (id, owner_id, starts_at, ends_at, label) VALUES (?, ?, ?, ?, ?) RETURNING *")
                .params(UUID.randomUUID(), ownerId, ts(start), ts(end), label).query(this::busy).single();
    }

    public boolean deleteBusy(UUID id, UUID ownerId) {
        return db.sql("DELETE FROM busy_blocks WHERE id = ? AND owner_id = ?").params(id, ownerId).update() == 1;
    }

    public List<BusyBlock> busy(UUID ownerId, Instant from, Instant to) {
        return db.sql("SELECT * FROM busy_blocks WHERE owner_id = ? AND starts_at < ? AND ends_at > ? ORDER BY starts_at")
                .params(ownerId, ts(to), ts(from)).query(this::busy).list();
    }

    // ---- approvals

    public Approval insertApproval(UUID ownerId, UUID sessionId, String kind, UUID planId, int planVersion,
                                   Map<String, Object> args, String summary, String conflict, Instant expiresAt) {
        return db.sql("""
                INSERT INTO approvals (id, owner_id, session_id, kind, plan_id, plan_version, args, summary, conflict, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING *
                """)
                .params(UUID.randomUUID(), ownerId, sessionId, kind, planId, planVersion, toJson(args), summary, conflict, ts(expiresAt))
                .query(this::approval).single();
    }

    public Optional<Approval> lockApproval(UUID id, UUID ownerId) {
        return db.sql("SELECT * FROM approvals WHERE id = ? AND owner_id = ? FOR UPDATE").params(id, ownerId)
                .query(this::approval).optional();
    }

    public Optional<String> decisionKey(UUID id) {
        return db.sql("SELECT decision_key FROM approvals WHERE id = ?").param(id).query(String.class).optional();
    }

    public Approval closeApproval(UUID id, String status, String decisionKey) {
        return db.sql("UPDATE approvals SET status = ?, decided_at = now(), decision_key = ? WHERE id = ? RETURNING *")
                .params(status, decisionKey, id).query(this::approval).single();
    }

    public List<Approval> pendingForPlan(UUID planId, String kind) {
        return db.sql("SELECT * FROM approvals WHERE plan_id = ? AND kind = ? AND status = 'pending'").params(planId, kind)
                .query(this::approval).list();
    }

    public List<Approval> approvals(UUID ownerId, String status, int limit) {
        return db.sql("SELECT * FROM approvals WHERE owner_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?")
                .params(ownerId, status, limit).query(this::approval).list();
    }

    public List<Approval> approvalsForPlan(UUID planId) {
        return db.sql("SELECT * FROM approvals WHERE plan_id = ? AND status = 'pending' ORDER BY created_at").param(planId)
                .query(this::approval).list();
    }

    /** Marks pending approvals past their deadline as expired; returns them so the caller can notify. */
    public List<Approval> expireDue(Instant now) {
        return db.sql("UPDATE approvals SET status = 'expired', decided_at = now() WHERE status = 'pending' AND expires_at <= ? RETURNING *")
                .param(ts(now)).query(this::approval).list();
    }

    public UUID approvalOwner(UUID id) {
        return db.sql("SELECT owner_id FROM approvals WHERE id = ?").param(id).query(UUID.class).single();
    }

    // ---- mapping

    private Plan plan(ResultSet rs, int i) throws SQLException {
        return new Plan(rs.getObject("id", UUID.class), rs.getObject("plan_date", LocalDate.class), rs.getInt("version"),
                rs.getString("status"));
    }

    private Task task(ResultSet rs, int i) throws SQLException {
        return new Task(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                rs.getObject("proposal_id", UUID.class), rs.getString("title"), instant(rs, "start_at"),
                rs.getInt("duration_min"), rs.getLong("cost_grosze"), rs.getString("reason"), rs.getString("status"),
                rs.getString("refusal_reason"), rs.getString("source"), (Integer) rs.getObject("actual_min"));
    }

    private BusyBlock busy(ResultSet rs, int i) throws SQLException {
        return new BusyBlock(rs.getObject("id", UUID.class), instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getString("label"));
    }

    @SuppressWarnings("unchecked")
    private Approval approval(ResultSet rs, int i) throws SQLException {
        Map<String, Object> args;
        try {
            args = json.readValue(rs.getString("args"), Map.class);
        } catch (JsonProcessingException e) {
            throw new SQLException(e);
        }
        return new Approval(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class), rs.getString("kind"),
                rs.getObject("plan_id", UUID.class), rs.getInt("plan_version"), args, rs.getString("summary"),
                rs.getString("conflict"), rs.getString("status"), instant(rs, "expires_at"), instant(rs, "decided_at"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        var t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
