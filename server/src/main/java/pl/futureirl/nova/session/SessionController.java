package pl.futureirl.nova.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.futureirl.nova.security.NovaUser;
import pl.futureirl.nova.session.SessionRepository.EventRow;
import pl.futureirl.nova.session.SessionRepository.MessageRow;
import pl.futureirl.nova.session.SessionRepository.SessionRow;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    public record ConnectedRequest(@Size(max = 200) String providerCallId) {}

    public record EndRequest(@Size(max = 40) @Pattern(regexp = "[a-z_]*") String reason) {}

    public record MessageRequest(@NotBlank @Pattern(regexp = "user|assistant") String role,
                                 @NotNull @Size(max = 20000) String content,
                                 boolean interrupted,
                                 @NotBlank @Size(max = 200) String providerItemId) {}

    public record EventRequest(@NotBlank @Size(max = 60) String type, @Size(max = 30) Map<String, Object> payload) {}

    public record SessionDetail(SessionRow session, List<MessageRow> messages, List<EventRow> events) {}

    /** Session open contract: returns an ephemeral credential the phone uses to connect to the provider directly. */
    @PostMapping
    ResponseEntity<SessionService.StartedSession> start(@AuthenticationPrincipal NovaUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.start(user.id()));
    }

    @GetMapping
    List<SessionRow> recent(@AuthenticationPrincipal NovaUser user, @RequestParam(defaultValue = "20") int limit) {
        return service.recent(user.id(), limit);
    }

    @GetMapping("/{id}")
    SessionDetail get(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return new SessionDetail(service.require(user.id(), id), service.messages(user.id(), id), service.events(user.id(), id));
    }

    @GetMapping("/{id}/metrics")
    LatencyReport metrics(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return service.metrics(user.id(), id);
    }

    @PostMapping("/{id}/connected")
    SessionRow connected(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id,
                         @Valid @RequestBody(required = false) ConnectedRequest body) {
        return service.connected(user.id(), id, body == null ? null : body.providerCallId());
    }

    @PostMapping("/{id}/end")
    SessionRow end(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id,
                   @Valid @RequestBody(required = false) EndRequest body) {
        return service.end(user.id(), id, body == null || body.reason() == null || body.reason().isBlank() ? null : body.reason());
    }

    @PostMapping("/{id}/messages")
    MessageRow message(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody MessageRequest body) {
        return service.addMessage(user.id(), id, body.role(), body.content(), body.interrupted(), body.providerItemId());
    }

    @PostMapping("/{id}/events")
    EventRow event(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody EventRequest body) {
        return service.addEvent(user.id(), id, body.type(), body.payload());
    }
}
