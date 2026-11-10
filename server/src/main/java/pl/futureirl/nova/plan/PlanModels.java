package pl.futureirl.nova.plan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlanModels {
    private PlanModels() {}

    public record Plan(UUID id, LocalDate date, int version, String status) {}

    public record Task(UUID id, UUID planId, UUID proposalId, String title, Instant startAt, int durationMin,
                       long costGrosze, String reason, String status, String refusalReason, String source) {
        public Instant endAt() {
            return startAt == null ? null : startAt.plusSeconds(durationMin * 60L);
        }
    }

    public record BusyBlock(UUID id, Instant startsAt, Instant endsAt, String label) {}

    public record Approval(UUID id, UUID sessionId, String kind, UUID planId, int planVersion, Map<String, Object> args,
                           String summary, String conflict, String status, Instant expiresAt, Instant decidedAt,
                           Instant createdAt) {}

    public record DayView(LocalDate date, String timeZone, Plan plan, List<Task> tasks, List<BusyBlock> busy,
                          List<Approval> approvals) {}

    public record Decision(String outcome, Approval approval, Plan plan) {}
}
