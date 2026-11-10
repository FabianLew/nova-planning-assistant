package pl.futureirl.nova.plan;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.futureirl.nova.common.ApiException;
import pl.futureirl.nova.common.UserTime;
import pl.futureirl.nova.events.EventLog;
import pl.futureirl.nova.memory.MemoryService;
import pl.futureirl.nova.plan.PlanModels.Approval;
import pl.futureirl.nova.plan.PlanModels.DayView;
import pl.futureirl.nova.plan.PlanModels.Decision;
import pl.futureirl.nova.plan.PlanModels.Plan;
import pl.futureirl.nova.plan.PlanModels.Task;

/**
 * Daily plan, local tasks, busy blocks and approvals (spec p.5–6). Any change made on the assistant's
 * initiative becomes an approval card; only the creator's tap in the UI applies it, exactly once, and only
 * while the plan is still at the version the card was made for.
 */
@Service
public class PlanService {
    public static final Duration APPROVAL_TTL = Duration.ofMinutes(15);

    private final PlanRepository repo;
    private final UserTime time;
    private final EventLog events;
    private final MemoryService memory;

    public PlanService(PlanRepository repo, UserTime time, EventLog events, MemoryService memory) {
        this.repo = repo;
        this.time = time;
        this.events = events;
        this.memory = memory;
    }

    public record ProposedItem(String title, String startTime, int durationMin, long costGrosze, String reason) {}

    // ---------------------------------------------------------------- reads

    public DayView day(UUID ownerId, LocalDate date) {
        var zone = time.zone(ownerId);
        var plan = repo.find(ownerId, date).orElse(null);
        var from = time.startOfDay(date, zone);
        var busy = repo.busy(ownerId, from, from.plus(Duration.ofDays(1)));
        if (plan == null) {
            return new DayView(date, zone.getId(), null, List.of(), busy, List.of());
        }
        return new DayView(date, zone.getId(), plan, repo.tasks(plan.id()), busy, repo.approvalsForPlan(plan.id()));
    }

    public List<Approval> pending(UUID ownerId) {
        return repo.approvals(ownerId, "pending", 50);
    }

    // ---------------------------------------------------------------- direct changes (creator's own action)

    /** Local task on the creator's command. Idempotent on {@code idempotencyKey} (the tool call id or a UI key). */
    @Transactional
    public Task createTask(UUID ownerId, LocalDate date, String title, String startTime, int durationMin, String source,
                           String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = repo.taskByKey(ownerId, idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }
        var zone = time.zone(ownerId);
        var start = startTime == null || startTime.isBlank() ? null : time.at(date, startTime, zone);
        var plan = repo.lockOrCreate(ownerId, date);
        if (idempotencyKey != null) {
            // re-check under the plan lock: two identical calls racing produce one row
            var existing = repo.taskByKey(ownerId, idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }
        var task = repo.insertTask(ownerId, plan.id(), null, title.strip(), start, durationMin, 0, null, "planned", source, idempotencyKey);
        var bumped = bump(ownerId, plan, null, "task.created", Map.of("added", taskJson(task, zone)));
        events.publish(ownerId, "task.created", Map.of("taskId", task.id().toString(), "date", date.toString(), "planVersion", bumped.version()));
        return task;
    }

    @Transactional
    public Task editTask(UUID ownerId, UUID taskId, String title, String startTime, Integer durationMin, int expectedPlanVersion) {
        var task = repo.task(taskId, ownerId).orElseThrow(() -> ApiException.notFound("task"));
        var plan = repo.lock(task.planId(), ownerId).orElseThrow(() -> ApiException.notFound("plan"));
        if (plan.version() != expectedPlanVersion) {
            throw ApiException.conflict("stale_version", "Plan changed, reload it");
        }
        var zone = time.zone(ownerId);
        var start = startTime == null ? null : time.at(plan.date(), startTime, zone);
        var updated = repo.updateTask(taskId, title, start, durationMin, null, null);
        bump(ownerId, plan, null, "task.edited", Map.of("before", taskJson(task, zone), "after", taskJson(updated, zone)));
        return updated;
    }

    /** done / skipped / refused (reason optional). A refusal is also remembered as an episode (spec p.4). */
    @Transactional
    public Task setStatus(UUID ownerId, UUID taskId, String status, String reason) {
        if (!List.of("done", "skipped", "refused", "planned").contains(status)) {
            throw new IllegalArgumentException("status must be done, skipped, refused or planned");
        }
        var task = repo.task(taskId, ownerId).orElseThrow(() -> ApiException.notFound("task"));
        if ("proposed".equals(task.status()) || "cancelled".equals(task.status())) {
            throw ApiException.conflict("task_not_active", "Only planned tasks can be marked");
        }
        var plan = repo.lock(task.planId(), ownerId).orElseThrow();
        var updated = repo.updateTask(taskId, null, null, null, status, "refused".equals(status) ? reason : null);
        bump(ownerId, plan, null, "task.status", Map.of("taskId", taskId.toString(), "from", task.status(), "to", status));
        if ("refused".equals(status)) {
            var text = "Odmowa: " + task.title() + " (" + plan.date() + ")" + (reason == null || reason.isBlank() ? "" : ", powód: " + reason.strip());
            memory.create(ownerId, "episode", text, true, 2, null, "Mój dzień");
        }
        events.publish(ownerId, "task.status", Map.of("taskId", taskId.toString(), "status", status));
        return updated;
    }

    @Transactional
    public PlanModels.BusyBlock addBusy(UUID ownerId, LocalDate date, String startTime, String endTime, String label) {
        var zone = time.zone(ownerId);
        var start = time.at(date, startTime, zone);
        var end = time.at(date, endTime, zone);
        if (!end.isAfter(start)) throw new IllegalArgumentException("end must be after start");
        var b = repo.insertBusy(ownerId, start, end, label.strip());
        events.publish(ownerId, "busy.changed", Map.of("date", date.toString()));
        return b;
    }

    @Transactional
    public void deleteBusy(UUID ownerId, UUID id) {
        if (!repo.deleteBusy(id, ownerId)) throw ApiException.notFound("busy block");
        events.publish(ownerId, "busy.changed", Map.of("id", id.toString()));
    }

    // ---------------------------------------------------------------- assistant proposals -> approval cards

    @Transactional
    public Map<String, Object> proposePlan(UUID ownerId, UUID sessionId, LocalDate date, List<ProposedItem> items) {
        if (items.isEmpty() || items.size() > 12) throw new IllegalArgumentException("plan needs 1 to 12 items");
        var zone = time.zone(ownerId);
        var plan = repo.lockOrCreate(ownerId, date);
        for (var old : repo.pendingForPlan(plan.id(), "plan_accept")) {
            repo.closeApproval(old.id(), "superseded", null);
            repo.setProposalTasks(old.id(), "cancelled");
            events.publish(ownerId, "approval.decided", Map.of("approvalId", old.id().toString(), "outcome", "superseded"));
        }
        var args = new LinkedHashMap<String, Object>();
        args.put("date", date.toString());
        args.put("items", items);
        var conflicts = new ArrayList<String>();
        var parsed = new ArrayList<Instant>();
        for (var it : items) {
            var start = time.at(date, it.startTime(), zone);
            parsed.add(start);
            // the assistant's earlier items are replaced on approval, so only busy time and the creator's tasks count
            conflicts.addAll(conflictsFor(ownerId, plan, null, start, it.durationMin(), zone, true));
        }
        var total = items.stream().mapToLong(ProposedItem::costGrosze).sum();
        var summary = "Plan na " + date + ": " + items.size() + " pozycji" + (total > 0 ? ", koszt " + pln(total) : "");
        var approval = repo.insertApproval(ownerId, sessionId, "plan_accept", plan.id(), plan.version(), args, summary,
                conflicts.isEmpty() ? null : String.join("; ", conflicts), time.now().plus(APPROVAL_TTL));
        var created = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < items.size(); i++) {
            var it = items.get(i);
            var t = repo.insertTask(ownerId, plan.id(), approval.id(), it.title().strip(), parsed.get(i), it.durationMin(),
                    it.costGrosze(), it.reason(), "proposed", "assistant", null);
            created.add(taskJson(t, zone));
        }
        events.publish(ownerId, "approval.requested", approvalEvent(approval));
        return pendingResult(approval, Map.of("items", created));
    }

    @Transactional
    public Map<String, Object> requestChange(UUID ownerId, UUID sessionId, UUID planId, int expectedVersion, UUID taskId,
                                             String newStartTime) {
        var zone = time.zone(ownerId);
        var plan = repo.lock(planId, ownerId).orElseThrow(() -> ApiException.notFound("plan"));
        if (plan.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "stale_version",
                    "Plan is at version " + plan.version() + ", read it again before proposing a change");
        }
        var task = repo.task(taskId, ownerId).filter(t -> t.planId().equals(planId))
                .orElseThrow(() -> ApiException.notFound("task in this plan"));
        if (!"planned".equals(task.status())) {
            throw ApiException.conflict("task_not_active", "Task is " + task.status());
        }
        var newStart = time.at(plan.date(), newStartTime, zone);
        var now = task.startAt() == null ? List.<String>of() : conflictsFor(ownerId, plan, taskId, task.startAt(), task.durationMin(), zone, false);
        var after = conflictsFor(ownerId, plan, taskId, newStart, task.durationMin(), zone, false);
        var conflict = new ArrayList<String>();
        if (!now.isEmpty()) conflict.add("Teraz: " + String.join(", ", now));
        if (!after.isEmpty()) conflict.add("Po zmianie nadal: " + String.join(", ", after));

        var args = new LinkedHashMap<String, Object>();
        args.put("taskId", taskId.toString());
        args.put("title", task.title());
        args.put("from", UserTime.hhmm(task.startAt(), zone));
        args.put("to", newStartTime);
        args.put("startAt", newStart.toString());
        var summary = "Przenieść „" + task.title() + "”? " + (task.startAt() == null ? "bez godziny" : UserTime.hhmm(task.startAt(), zone))
                + " → " + newStartTime;
        var approval = repo.insertApproval(ownerId, sessionId, "plan_change", plan.id(), plan.version(), args, summary,
                conflict.isEmpty() ? null : String.join(". ", conflict), time.now().plus(APPROVAL_TTL));
        events.publish(ownerId, "approval.requested", approvalEvent(approval));
        return pendingResult(approval, Map.of());
    }

    // ---------------------------------------------------------------- the creator's decision

    /**
     * Applies or rejects an approval card. Idempotent on {@code decisionKey}: a repeated tap returns the first
     * outcome. A card for an older plan version, or past its deadline, never changes the plan.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Decision decide(UUID ownerId, UUID approvalId, boolean approve, String decisionKey) {
        var approval = repo.lockApproval(approvalId, ownerId).orElseThrow(() -> ApiException.notFound("approval"));
        if (!"pending".equals(approval.status())) {
            if (decisionKey.equals(repo.decisionKey(approvalId).orElse(null))) {
                return new Decision(approval.status(), approval, null);
            }
            throw ApiException.conflict("already_decided", "Card is already " + approval.status());
        }
        if (!time.now().isBefore(approval.expiresAt())) {
            return close(ownerId, approval, "expired", decisionKey, null);
        }
        var plan = repo.lock(approval.planId(), ownerId).orElseThrow();
        if (plan.version() != approval.planVersion()) {
            return close(ownerId, approval, "superseded", decisionKey, plan);
        }
        if (!approve) {
            return close(ownerId, approval, "rejected", decisionKey, plan);
        }
        var zone = time.zone(ownerId);
        Plan updated;
        if ("plan_change".equals(approval.kind())) {
            var taskId = UUID.fromString((String) approval.args().get("taskId"));
            var task = repo.task(taskId, ownerId).orElseThrow();
            if (!"planned".equals(task.status())) {
                return close(ownerId, approval, "superseded", decisionKey, plan);
            }
            var moved = repo.updateTask(taskId, null, Instant.parse((String) approval.args().get("startAt")), null, null, null);
            updated = bump(ownerId, plan, null, "approval:" + approval.id(),
                    Map.of("before", taskJson(task, zone), "after", taskJson(moved, zone)));
        } else {
            int cancelled = repo.cancelSupersededAssistantTasks(plan.id(), approval.id());
            int accepted = repo.setProposalTasks(approval.id(), "planned");
            updated = bump(ownerId, plan, "approved", "approval:" + approval.id(),
                    Map.of("accepted", accepted, "replaced", cancelled));
        }
        var closed = repo.closeApproval(approval.id(), "approved", decisionKey);
        events.publish(ownerId, "approval.decided", Map.of("approvalId", approval.id().toString(), "outcome", "approved",
                "planVersion", updated.version()));
        return new Decision("approved", closed, updated);
    }

    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void expireDue() {
        for (var a : repo.expireDue(time.now())) {
            repo.setProposalTasks(a.id(), "cancelled");
            events.publish(repo.approvalOwner(a.id()), "approval.decided", Map.of("approvalId", a.id().toString(), "outcome", "expired"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private Decision close(UUID ownerId, Approval approval, String outcome, String key, Plan plan) {
        var closed = repo.closeApproval(approval.id(), outcome, key);
        if ("plan_accept".equals(approval.kind())) repo.setProposalTasks(approval.id(), "cancelled");
        events.publish(ownerId, "approval.decided", Map.of("approvalId", approval.id().toString(), "outcome", outcome));
        return new Decision(outcome, closed, plan);
    }

    private Plan bump(UUID ownerId, Plan plan, String status, String cause, Map<String, Object> diff) {
        var updated = repo.bump(plan.id(), status);
        repo.recordChange(ownerId, plan.id(), plan.version(), updated.version(), cause, diff);
        events.publish(ownerId, "plan.updated", Map.of("planId", plan.id().toString(), "date", plan.date().toString(),
                "version", updated.version()));
        return updated;
    }

    /** Overlaps with busy blocks and other active tasks, described for the card and the model. */
    List<String> conflictsFor(UUID ownerId, Plan plan, UUID ignoreTaskId, Instant start, int durationMin, ZoneId zone,
                              boolean ignoreAssistantTasks) {
        var end = start.plus(Duration.ofMinutes(durationMin));
        var out = new ArrayList<String>();
        for (var b : repo.busy(ownerId, start, end)) {
            out.add(b.label() + " " + UserTime.hhmm(b.startsAt(), zone) + "–" + UserTime.hhmm(b.endsAt(), zone));
        }
        for (var t : repo.tasks(plan.id())) {
            if (t.id().equals(ignoreTaskId) || t.startAt() == null || !List.of("planned", "done").contains(t.status())) continue;
            if (ignoreAssistantTasks && "assistant".equals(t.source()) && "planned".equals(t.status())) continue;
            if (t.startAt().isBefore(end) && t.endAt().isAfter(start)) {
                out.add(t.title() + " " + UserTime.hhmm(t.startAt(), zone) + "–" + UserTime.hhmm(t.endAt(), zone));
            }
        }
        return out.stream().distinct().toList();
    }

    static Map<String, Object> taskJson(Task t, ZoneId zone) {
        var m = new HashMap<String, Object>();
        m.put("taskId", t.id().toString());
        m.put("title", t.title());
        m.put("start", UserTime.hhmm(t.startAt(), zone));
        m.put("durationMin", t.durationMin());
        m.put("status", t.status());
        if (t.costGrosze() > 0) m.put("cost", pln(t.costGrosze()));
        return m;
    }

    private static Map<String, Object> approvalEvent(Approval a) {
        var m = new HashMap<String, Object>();
        m.put("approvalId", a.id().toString());
        m.put("kind", a.kind());
        m.put("summary", a.summary());
        if (a.conflict() != null) m.put("conflict", a.conflict());
        m.put("expiresAt", a.expiresAt().toString());
        return m;
    }

    private static Map<String, Object> pendingResult(Approval a, Map<String, Object> extra) {
        var m = new LinkedHashMap<String, Object>();
        m.put("status", "pending_approval");
        m.put("approvalId", a.id().toString());
        m.put("summary", a.summary());
        if (a.conflict() != null) m.put("conflict", a.conflict());
        m.put("expiresAt", a.expiresAt().toString());
        m.put("note", "Twórca musi zatwierdzić kartę na ekranie. Nie mów, że zmiana jest zrobiona.");
        m.putAll(extra);
        return m;
    }

    public static String pln(long grosze) {
        return String.format(java.util.Locale.forLanguageTag("pl-PL"), "%.2f zł", grosze / 100.0);
    }
}
