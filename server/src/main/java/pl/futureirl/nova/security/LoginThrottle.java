package pl.futureirl.nova.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.stereotype.Component;

/** Blocks login after too many failures in a sliding window. One account, so one global window is enough. */
@Component
public class LoginThrottle {
    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Deque<Instant> failures = new ArrayDeque<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean blocked() {
        prune();
        return failures.size() >= MAX_FAILURES;
    }

    public synchronized void failed() {
        prune();
        failures.addLast(clock.instant());
    }

    public synchronized void succeeded() {
        failures.clear();
    }

    private void prune() {
        var cutoff = clock.instant().minus(WINDOW);
        while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
            failures.removeFirst();
        }
    }
}
