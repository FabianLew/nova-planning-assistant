package pl.futureirl.nova.memory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.futureirl.nova.common.ApiException;
import pl.futureirl.nova.events.EventLog;

/**
 * Durable memory (spec p.4). Facts the creator states explicitly are confirmed; the assistant's guesses go to
 * a pending queue for the creator to confirm. Every change bumps the version and is audited without content.
 */
@Service
public class MemoryService {
    private final MemoryRepository repo;
    private final EventLog events;

    public MemoryService(MemoryRepository repo, EventLog events) {
        this.repo = repo;
        this.events = events;
    }

    public List<Memory> list(UUID ownerId, String status) {
        return repo.list(ownerId, status);
    }

    public List<Memory> search(UUID ownerId, String query, int limit) {
        return repo.search(ownerId, query, limit);
    }

    @Transactional
    public Memory create(UUID ownerId, String kind, String content, boolean confirmed, int importance, UUID sessionId, String note) {
        var text = content.strip();
        var existing = repo.findSameContent(ownerId, text);
        if (existing.isPresent()) {
            return existing.get(); // saying the same thing twice does not duplicate it
        }
        var m = repo.insert(ownerId, kind, text, confirmed ? "confirmed" : "pending", importance, sessionId, note);
        repo.audit(ownerId, m.id(), "create", m.version());
        changed(ownerId, m, "created");
        return m;
    }

    @Transactional
    public Memory edit(UUID ownerId, UUID id, String content, Integer importance, int expectedVersion) {
        var m = lockOwned(ownerId, id);
        if (m.version() != expectedVersion) {
            throw ApiException.conflict("stale_version", "Memory was changed elsewhere");
        }
        var updated = repo.update(id, content == null ? null : content.strip(), importance, null);
        repo.audit(ownerId, id, "edit", updated.version());
        changed(ownerId, updated, "edited");
        return updated;
    }

    @Transactional
    public Memory confirm(UUID ownerId, UUID id) {
        return setStatus(ownerId, id, "confirmed", "confirm");
    }

    @Transactional
    public Memory reject(UUID ownerId, UUID id) {
        return setStatus(ownerId, id, "rejected", "reject");
    }

    @Transactional
    public void delete(UUID ownerId, UUID id) {
        var m = lockOwned(ownerId, id);
        repo.erase(id);
        repo.audit(ownerId, id, "delete", m.version() + 1);
        events.publish(ownerId, "memory.deleted", Map.of("id", id.toString()));
    }

    private Memory setStatus(UUID ownerId, UUID id, String status, String op) {
        lockOwned(ownerId, id);
        var updated = repo.update(id, null, null, status);
        repo.audit(ownerId, id, op, updated.version());
        changed(ownerId, updated, op);
        return updated;
    }

    private Memory lockOwned(UUID ownerId, UUID id) {
        return repo.lock(id, ownerId).orElseThrow(() -> ApiException.notFound("memory"));
    }

    private void changed(UUID ownerId, Memory m, String change) {
        events.publish(ownerId, "memory.changed", Map.of("id", m.id().toString(), "change", change, "status", m.status()));
    }
}
