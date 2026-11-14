package pl.futureirl.nova.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import pl.futureirl.nova.security.UserRepository;

/** Converts between the creator's local wall time ("2026-11-12", "17:30") and stored instants. */
@Component
public class UserTime {
    public static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final UserRepository users;
    private final Clock clock;

    public UserTime(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    public ZoneId zone(UUID ownerId) {
        return ZoneId.of(users.timeZone(ownerId));
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today(UUID ownerId) {
        return LocalDate.now(clock.withZone(zone(ownerId)));
    }

    public LocalDate parseDate(String value, UUID ownerId) {
        if (value == null || value.isBlank() || "today".equalsIgnoreCase(value) || "dzisiaj".equalsIgnoreCase(value)) {
            return today(ownerId);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD");
        }
    }

    public Instant at(LocalDate date, String hhmm, ZoneId zone) {
        try {
            return date.atTime(LocalTime.parse(hhmm.trim(), HH_MM)).atZone(zone).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("time must be HH:mm");
        }
    }

    public Instant startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    public static String hhmm(Instant t, ZoneId zone) {
        return t == null ? null : HH_MM.format(t.atZone(zone));
    }
}
