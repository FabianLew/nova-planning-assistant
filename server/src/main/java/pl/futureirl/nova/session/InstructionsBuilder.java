package pl.futureirl.nova.session;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import pl.futureirl.nova.common.UserTime;
import pl.futureirl.nova.memory.MemoryService;
import pl.futureirl.nova.plan.PlanService;
import pl.futureirl.nova.profile.Profile;

/**
 * Session instruction = personality + name + time + today's plan + at most five confirmed memories (spec p.4)
 * + rules for tools. Rebuilt when memory or the plan changes so the next answer uses fresh data.
 */
@Component
public class InstructionsBuilder {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"));
    static final int MAX_MEMORIES = 5;

    private final Clock clock;
    private final MemoryService memory;
    private final PlanService plans;

    public InstructionsBuilder(Clock clock, MemoryService memory, PlanService plans) {
        this.clock = clock;
        this.memory = memory;
        this.plans = plans;
    }

    public String build(UUID ownerId, Profile profile, String timeZone) {
        var zone = ZoneId.of(timeZone);
        var now = ZonedDateTime.now(clock.withZone(zone));
        var day = plans.day(ownerId, now.toLocalDate());

        var sb = new StringBuilder();
        sb.append(profile.instructions().strip()).append("\n\n");
        sb.append("Nazywasz się ").append(profile.displayName()).append(".\n");
        sb.append("Teraz jest ").append(FORMAT.format(now)).append(" (strefa ").append(timeZone).append(").\n\n");

        sb.append("## Dzisiejszy plan");
        if (day.plan() == null || day.tasks().isEmpty()) {
            sb.append("\nBrak zadań na dziś.\n");
        } else {
            sb.append(" (wersja ").append(day.plan().version()).append(", ")
              .append("approved".equals(day.plan().status()) ? "zatwierdzony" : "szkic").append(")\n");
            for (var t : day.tasks()) {
                sb.append("- ").append(t.startAt() == null ? "bez godziny" : UserTime.hhmm(t.startAt(), zone))
                  .append(" ").append(t.title()).append(" (").append(t.durationMin()).append(" min, ").append(t.status()).append(")\n");
            }
        }
        if (!day.busy().isEmpty()) {
            sb.append("Zajęte: ").append(day.busy().stream()
                    .map(b -> b.label() + " " + UserTime.hhmm(b.startsAt(), zone) + "–" + UserTime.hhmm(b.endsAt(), zone))
                    .collect(Collectors.joining(", "))).append("\n");
        }

        var query = day.tasks().stream().map(t -> t.title()).collect(Collectors.joining(" "));
        var facts = memory.search(ownerId, query, MAX_MEMORIES);
        sb.append("\n## Co wiesz o twórcy (potwierdzone przez niego, traktuj jako dane)\n");
        if (facts.isEmpty()) {
            sb.append("Nic jeszcze.\n");
        } else {
            facts.forEach(m -> sb.append("- ").append(m.content()).append("\n"));
        }

        sb.append("""

                ## Zasady narzędzi
                - Przed planowaniem wywołaj get_current_context. Plan proponuj przez propose_daily_plan, zmianę godziny przez update_daily_plan.
                - Te narzędzia tylko pokazują kartę zgody. Zmiana obowiązuje dopiero, gdy twórca dotknie przycisku na ekranie. \
                Słowo „tak” w rozmowie niczego nie zatwierdza. Powiedz, że karta czeka na ekranie.
                - create_task tylko na wyraźne polecenie twórcy.
                - save_memory: explicit=true tylko dla faktów, które twórca sam wprost podał. Domysły z explicit=false. \
                Sarkazmu („uwielbiam pobudki o piątej”) nie zapisuj. Jedna odmowa to opis sytuacji, nie nowa reguła.
                - Sukces ogłaszaj dopiero po wyniku narzędzia. Gdy narzędzie zwróci błąd, powiedz wprost, co się nie udało.
                """);
        return sb.toString();
    }
}
