package pl.futureirl.nova.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.futureirl.nova.common.ApiException;
import pl.futureirl.nova.config.NovaProperties;
import pl.futureirl.nova.profile.ProfileRepository;
import pl.futureirl.nova.realtime.RealtimeProvider;
import pl.futureirl.nova.security.UserRepository;
import pl.futureirl.nova.session.SessionRepository.EventRow;
import pl.futureirl.nova.session.SessionRepository.MessageRow;
import pl.futureirl.nova.session.SessionRepository.SessionRow;

@Service
public class SessionService {
    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z]+(\\.[a-z_]+){1,3}");
    private static final int CLIENT_SECRET_TTL_SECONDS = 120;

    private final SessionRepository sessions;
    private final ProfileRepository profiles;
    private final UserRepository users;
    private final RealtimeProvider provider;
    private final InstructionsBuilder instructions;
    private final NovaProperties.Limits limits;

    public SessionService(SessionRepository sessions, ProfileRepository profiles, UserRepository users,
                          RealtimeProvider provider, InstructionsBuilder instructions, NovaProperties props) {
        this.sessions = sessions;
        this.profiles = profiles;
        this.users = users;
        this.provider = provider;
        this.instructions = instructions;
        this.limits = props.limits();
    }

    public record StartedSession(UUID sessionId, String clientSecret, Instant expiresAt, String rtcUrl, String model,
                                 String voice, String displayName, String turnDetection, int vadSilenceMs,
                                 int maxDurationSeconds) {}

    public StartedSession start(UUID ownerId) {
        if (sessions.countStartedSince(ownerId, Instant.now().minus(Duration.ofHours(1))) >= limits.maxStartsPerHour()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_sessions",
                    "Session start limit reached for this hour");
        }
        var profile = profiles.get(ownerId);
        sessions.endOpen(ownerId, "superseded");
        var id = UUID.randomUUID();
        sessions.insert(id, ownerId, provider.name(), provider.model(), profile.voice());
        try {
            var spec = new RealtimeProvider.SessionSpec(instructions.build(profile, users.timeZone(ownerId)),
                    profile.voice(), profile.turnDetection(), profile.vadSilenceMs(), profile.vadEagerness(),
                    CLIENT_SECRET_TTL_SECONDS);
            var secret = provider.createClientSecret(spec);
            return new StartedSession(id, secret.value(), secret.expiresAt(), secret.rtcUrl(), provider.model(),
                    profile.voice(), profile.displayName(), profile.turnDetection(), profile.vadSilenceMs(),
                    limits.maxSessionMinutes() * 60);
        } catch (RuntimeException e) {
            sessions.markFailed(id, e instanceof ApiException a ? a.code() : "provider_error");
            throw e;
        }
    }

    public SessionRow connected(UUID ownerId, UUID sessionId, String providerCallId) {
        if (!sessions.markConnected(sessionId, ownerId, providerCallId)) {
            throw closedOrMissing(ownerId, sessionId);
        }
        return require(ownerId, sessionId);
    }

    public SessionRow end(UUID ownerId, UUID sessionId, String reason) {
        sessions.markEnded(sessionId, ownerId, reason == null ? "user" : reason);
        return require(ownerId, sessionId); // ending twice is fine
    }

    /** Idempotent per provider item id: a retried POST returns the stored row. */
    @Transactional
    public MessageRow addMessage(UUID ownerId, UUID sessionId, String role, String content, boolean interrupted,
                                 String providerItemId) {
        sessions.lock(sessionId, ownerId).orElseThrow(() -> ApiException.notFound("session"));
        return sessions.findMessage(sessionId, providerItemId)
                .orElseGet(() -> sessions.insertMessage(sessionId, ownerId, role, content, interrupted, providerItemId));
    }

    @Transactional
    public EventRow addEvent(UUID ownerId, UUID sessionId, String type, Map<String, Object> payload) {
        if (!EVENT_TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("event type must look like 'connection.lost'");
        }
        sessions.lock(sessionId, ownerId).orElseThrow(() -> ApiException.notFound("session"));
        return sessions.insertEvent(sessionId, ownerId, type, payload);
    }

    public SessionRow require(UUID ownerId, UUID sessionId) {
        return sessions.find(sessionId, ownerId).orElseThrow(() -> ApiException.notFound("session"));
    }

    public List<SessionRow> recent(UUID ownerId, int limit) {
        return sessions.recent(ownerId, Math.max(1, Math.min(limit, 100)));
    }

    public List<MessageRow> messages(UUID ownerId, UUID sessionId) {
        require(ownerId, sessionId);
        return sessions.messages(sessionId, ownerId);
    }

    public List<EventRow> events(UUID ownerId, UUID sessionId) {
        require(ownerId, sessionId);
        return sessions.events(sessionId, ownerId);
    }

    public LatencyReport metrics(UUID ownerId, UUID sessionId) {
        return LatencyReport.from(events(ownerId, sessionId));
    }

    private ApiException closedOrMissing(UUID ownerId, UUID sessionId) {
        require(ownerId, sessionId);
        return ApiException.conflict("session_closed", "Session is already closed");
    }
}
