package pl.futureirl.nova.experiment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.futureirl.nova.security.NovaUser;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {
    static final String HHMM = "([01]\\d|2[0-3]):[0-5]\\d";

    private final ExperimentService service;

    public ExperimentController(ExperimentService service) {
        this.service = service;
    }

    public record ConfigRequest(@NotBlank @Size(max = 300) String goal, @NotNull LocalDate startDate,
                                @NotBlank @Pattern(regexp = HHMM) String workStart, @NotBlank @Pattern(regexp = HHMM) String workEnd,
                                @Size(max = 20) List<@Size(max = 60) String> exclusions,
                                @NotNull @DecimalMin("0") BigDecimal budgetPln, @NotNull @DecimalMin("0") BigDecimal apiLimitPln,
                                @Pattern(regexp = HHMM) String dayStart,
                                @Min(1) @Max(5) Integer baselineRating, @Min(0) @Max(200) Integer baselineActivities,
                                @Size(max = 300) String baselineNote) {
        ExperimentService.Config toConfig() {
            var baseline = new LinkedHashMap<String, Object>();
            if (baselineRating != null) baseline.put("usualWeekRating", baselineRating);
            if (baselineActivities != null) baseline.put("usualActivities", baselineActivities);
            if (baselineNote != null && !baselineNote.isBlank()) baseline.put("note", baselineNote.strip());
            return new ExperimentService.Config(goal, startDate, workStart, workEnd,
                    exclusions == null ? List.of() : exclusions.stream().map(String::strip).filter(s -> !s.isEmpty()).toList(),
                    grosze(budgetPln), grosze(apiLimitPln), dayStart == null ? "04:00" : dayStart, baseline);
        }
    }

    public record TransitionRequest(@NotBlank @Pattern(regexp = "activate|pause|resume|end") String action, @Size(max = 300) String note) {}

    public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 1000) String comment) {}

    public record ExpenseRequest(@NotNull @DecimalMin("0.01") BigDecimal amountPln, @NotBlank @Size(max = 200) String description,
                                 UUID taskId, @Size(max = 100) String key) {}

    /** Current experiment with day, budget, API spend and reviews; 204 when none was ever created. */
    @GetMapping("/current")
    ResponseEntity<ExperimentModels.Status> current(@AuthenticationPrincipal NovaUser user) {
        return service.status(user.id()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping
    ExperimentModels.Experiment create(@AuthenticationPrincipal NovaUser user, @Valid @RequestBody ConfigRequest body) {
        return service.create(user.id(), body.toConfig());
    }

    @PutMapping("/{id}")
    ExperimentModels.Experiment update(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody ConfigRequest body) {
        return service.update(user.id(), id, body.toConfig());
    }

    @PostMapping("/{id}/transition")
    ExperimentModels.Experiment transition(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody TransitionRequest body) {
        return service.transition(user.id(), id, body.action(), body.note());
    }

    @PutMapping("/current/reviews/{date}")
    ExperimentModels.Review review(@AuthenticationPrincipal NovaUser user, @PathVariable LocalDate date, @Valid @RequestBody ReviewRequest body) {
        return service.review(user.id(), date, body.rating(), body.comment());
    }

    @PostMapping("/current/expenses")
    ExperimentModels.Expense expense(@AuthenticationPrincipal NovaUser user, @Valid @RequestBody ExpenseRequest body) {
        return service.recordExpense(user.id(), grosze(body.amountPln()), body.description(), body.taskId(),
                body.key() == null ? null : "ui:" + body.key());
    }

    @GetMapping("/{id}/report")
    ExperimentModels.Report report(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return service.report(user.id(), id);
    }

    static long grosze(BigDecimal pln) {
        return pln.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
