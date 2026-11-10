package pl.futureirl.nova.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.futureirl.nova.common.UserTime;
import pl.futureirl.nova.security.NovaUser;

@RestController
@RequestMapping("/api")
public class DayController {
    static final String HHMM = "([01]\\d|2[0-3]):[0-5]\\d";

    private final PlanService plans;
    private final UserTime time;

    public DayController(PlanService plans, UserTime time) {
        this.plans = plans;
        this.time = time;
    }

    public record TaskRequest(@NotBlank @Size(max = 200) String title, @Pattern(regexp = HHMM) String startTime,
                              @Min(1) @Max(1440) Integer durationMin, @Size(max = 100) String key) {}

    public record EditTaskRequest(@Size(min = 1, max = 200) String title, @Pattern(regexp = HHMM) String startTime,
                                  @Min(1) @Max(1440) Integer durationMin, int expectedPlanVersion) {}

    public record StatusRequest(@NotBlank @Pattern(regexp = "done|skipped|refused|planned") String status,
                                @Size(max = 300) String reason, @Min(0) @Max(1440) Integer actualMin) {}

    public record BusyRequest(@NotBlank @Pattern(regexp = HHMM) String startTime, @NotBlank @Pattern(regexp = HHMM) String endTime,
                              @NotBlank @Size(max = 120) String label) {}

    public record DecisionRequest(@NotBlank @Pattern(regexp = "approve|reject") String decision,
                                  @NotBlank @Size(max = 100) String key) {}

    @GetMapping("/days/today")
    PlanModels.DayView today(@AuthenticationPrincipal NovaUser user) {
        return plans.day(user.id(), time.today(user.id()));
    }

    @GetMapping("/days/{date}")
    PlanModels.DayView day(@AuthenticationPrincipal NovaUser user, @PathVariable LocalDate date) {
        return plans.day(user.id(), date);
    }

    @PostMapping("/days/{date}/tasks")
    PlanModels.Task addTask(@AuthenticationPrincipal NovaUser user, @PathVariable LocalDate date, @Valid @RequestBody TaskRequest body) {
        return plans.createTask(user.id(), date, body.title(), body.startTime(),
                body.durationMin() == null ? 30 : body.durationMin(), "user", body.key() == null ? null : "ui:" + body.key());
    }

    @PostMapping("/days/{date}/busy")
    PlanModels.BusyBlock addBusy(@AuthenticationPrincipal NovaUser user, @PathVariable LocalDate date, @Valid @RequestBody BusyRequest body) {
        return plans.addBusy(user.id(), date, body.startTime(), body.endTime(), body.label());
    }

    @DeleteMapping("/busy/{id}")
    ResponseEntity<Void> deleteBusy(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        plans.deleteBusy(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/tasks/{id}")
    PlanModels.Task editTask(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody EditTaskRequest body) {
        return plans.editTask(user.id(), id, body.title(), body.startTime(), body.durationMin(), body.expectedPlanVersion());
    }

    @PostMapping("/tasks/{id}/status")
    PlanModels.Task status(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
        return plans.setStatus(user.id(), id, body.status(), body.reason(), body.actualMin());
    }

    @GetMapping("/approvals")
    List<PlanModels.Approval> pending(@AuthenticationPrincipal NovaUser user) {
        return plans.pending(user.id());
    }

    /** The only way an approval card is applied: an explicit tap in the UI (spec p.5). */
    @PostMapping("/approvals/{id}/decision")
    PlanModels.Decision decide(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        return plans.decide(user.id(), id, "approve".equals(body.decision()), body.key());
    }
}
