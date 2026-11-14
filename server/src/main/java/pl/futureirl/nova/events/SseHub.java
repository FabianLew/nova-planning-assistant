package pl.futureirl.nova.events;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live SSE connections per owner. */
@Component
public class SseHub {
    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(UUID ownerId) {
        var emitter = new SseEmitter(0L); // no server timeout; the client reconnects with Last-Event-ID
        var set = emitters.computeIfAbsent(ownerId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public boolean send(SseEmitter emitter, EventLog.OwnerEvent e) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(e.seq())).name(e.type())
                    .data(Map.of("seq", e.seq(), "type", e.type(), "payload", e.payload(), "occurredAt", e.occurredAt().toString()),
                            MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            emitter.completeWithError(ex);
            return false;
        }
    }

    void push(UUID ownerId, EventLog.OwnerEvent e) {
        var set = emitters.get(ownerId);
        if (set == null) return;
        for (var emitter : set) {
            if (!send(emitter, e)) set.remove(emitter);
        }
    }

    /** Keeps proxies (Railway, mobile networks) from closing idle streams. */
    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        emitters.values().forEach(set -> set.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
                return false;
            } catch (IOException | IllegalStateException ex) {
                emitter.completeWithError(ex);
                return true;
            }
        }));
    }

    int connections(UUID ownerId) {
        var set = emitters.get(ownerId);
        return set == null ? 0 : set.size();
    }
}
