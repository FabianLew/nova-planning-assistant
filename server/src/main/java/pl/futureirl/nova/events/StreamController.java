package pl.futureirl.nova.events;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.futureirl.nova.security.NovaUser;

/** SSE: status changes, tool results and approval requests. After a break the client resumes from its last seq. */
@RestController
@RequestMapping("/api/events")
public class StreamController {
    private final EventLog log;
    private final SseHub hub;

    public StreamController(EventLog log, SseHub hub) {
        this.log = log;
        this.hub = hub;
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    SseEmitter stream(@AuthenticationPrincipal NovaUser user,
                      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                      @RequestParam(value = "after", required = false) Long after) {
        // On reconnect EventSource sends Last-Event-ID but repeats the original URL, so the header wins.
        long from = lastEventId != null ? parse(lastEventId) : after != null ? after : 0;
        var emitter = hub.register(user.id());
        // Replay what the client missed; events committed meanwhile may arrive twice and are deduped by seq.
        for (var e : log.since(user.id(), from, 500)) {
            if (!hub.send(emitter, e)) break;
        }
        return emitter;
    }

    /** Plain JSON variant of the replay, for clients that poll. */
    @GetMapping
    List<EventLog.OwnerEvent> since(@AuthenticationPrincipal NovaUser user, @RequestParam(defaultValue = "0") long after) {
        return log.since(user.id(), after, 500);
    }

    @GetMapping("/last")
    long last(@AuthenticationPrincipal NovaUser user) {
        return log.lastSeq(user.id());
    }

    private static long parse(String id) {
        try {
            return id == null ? 0 : Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
