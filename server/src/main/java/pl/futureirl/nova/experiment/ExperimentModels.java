package pl.futureirl.nova.experiment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExperimentModels {
    private ExperimentModels() {}

    public record Experiment(UUID id, String goal, LocalDate startDate, int days, String workStart, String workEnd,
                             List<String> exclusions, long budgetGrosze, long apiLimitGrosze, String dayStart,
                             Map<String, Object> baseline, int rulesVersion, String status, Instant activatedAt,
                             Instant endedAt) {
        public LocalDate endDate() {
            return startDate.plusDays(days - 1L);
        }
    }

    public record Budget(long limitGrosze, long spentGrosze, long reservedGrosze, long availableGrosze, boolean overLimit) {}

    public record Review(LocalDate date, int rating, String comment, Instant updatedAt) {}

    public record ApiUsage(long minutes, long estimatedGrosze, long limitGrosze, boolean overLimit) {}

    public record Status(Experiment experiment, int dayIndex, LocalDate experimentDate, Budget budget, ApiUsage api,
                         int refusals, int tasksDone, int tasksTotal, Review todayReview, Review yesterdayReview) {}

    public record DayReport(int day, LocalDate date, Integer rating, String comment, int planned, int done, int skipped,
                            int refused, long spentGrosze, long plannedMinutes, long actualMinutes) {}

    public record Refusal(LocalDate date, String title, String reason) {}

    public record Expense(UUID id, LocalDate date, long amountGrosze, String description, UUID taskId) {}

    public record Report(Experiment experiment, List<DayReport> days, Double averageRating, int planned, int done,
                         int refused, int skipped, Budget budget, List<Expense> expenses, List<Refusal> refusals,
                         ApiUsage api, Map<String, Object> baseline) {}
}
