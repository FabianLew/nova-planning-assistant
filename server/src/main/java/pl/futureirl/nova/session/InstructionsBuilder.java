package pl.futureirl.nova.session;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import pl.futureirl.nova.profile.Profile;

/** Builds the session instruction: personality + name + current time context. Later steps add memory and plan. */
@Component
public class InstructionsBuilder {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"));

    private final Clock clock;

    public InstructionsBuilder() {
        this(Clock.systemUTC());
    }

    InstructionsBuilder(Clock clock) {
        this.clock = clock;
    }

    public String build(Profile profile, String timeZone) {
        var now = ZonedDateTime.now(clock.withZone(ZoneId.of(timeZone)));
        return profile.instructions().strip() + "\n\n"
                + "Nazywasz się " + profile.displayName() + ".\n"
                + "Teraz jest " + FORMAT.format(now) + " (strefa " + timeZone + ").\n"
                + "Na razie nie masz dostępu do kalendarza, pamięci ani narzędzi. Jeśli twórca o nie prosi, "
                + "powiedz krótko, że to dopiero będzie.";
    }
}
