package pl.futureirl.nova.memory;

import java.time.Instant;
import java.util.UUID;

public record Memory(UUID id, String kind, String content, String status, int importance, UUID sourceSessionId,
                     String sourceNote, int version, Instant createdAt, Instant updatedAt) {}
