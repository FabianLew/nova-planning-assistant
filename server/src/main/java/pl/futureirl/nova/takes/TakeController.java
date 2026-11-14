package pl.futureirl.nova.takes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.futureirl.nova.common.UserTime;
import pl.futureirl.nova.security.NovaUser;

@RestController
@RequestMapping("/api/takes")
public class TakeController {
    private final TakeService takes;
    private final UserTime time;

    public TakeController(TakeService takes, UserTime time) {
        this.takes = takes;
        this.time = time;
    }

    public record StartRequest(UUID sessionId, @Size(max = 120) String label) {}

    public record EventRequest(@Size(max = 100) String clientEventId, @NotBlank @Size(max = 40) String type,
                               @Size(max = 20) String speaker, @Size(max = 20000) String text, Long occurredAtMs,
                               @Size(max = 20) Map<String, Object> payload) {}

    public record EditRequest(@Size(max = 20000) String text, Boolean isPrivate) {}

    @PostMapping
    TakeService.Take start(@AuthenticationPrincipal NovaUser user, @Valid @RequestBody(required = false) StartRequest body) {
        return takes.start(user.id(), body == null ? null : body.sessionId(), body == null ? null : body.label());
    }

    @GetMapping("/open")
    ResponseEntity<TakeService.Take> open(@AuthenticationPrincipal NovaUser user) {
        return takes.open(user.id()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/pause")
    TakeService.Take pause(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return takes.pause(user.id(), id, false);
    }

    /** Privacy button: pauses the record; the client also stops the microphone. */
    @PostMapping("/{id}/privacy")
    TakeService.Take privacy(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return takes.pause(user.id(), id, true);
    }

    @PostMapping("/{id}/resume")
    TakeService.Take resume(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return takes.resume(user.id(), id);
    }

    @PostMapping("/{id}/stop")
    TakeService.Take stop(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return takes.stop(user.id(), id);
    }

    @PostMapping("/{id}/events")
    TakeService.TakeEvent log(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody EventRequest body) {
        return takes.log(user.id(), id, body.clientEventId(), body.type(), body.speaker(), body.text(),
                body.occurredAtMs() == null ? null : Instant.ofEpochMilli(body.occurredAtMs()), body.payload());
    }

    @PatchMapping("/{id}/events/{eventId}")
    TakeService.TakeEvent edit(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @PathVariable UUID eventId,
                               @Valid @RequestBody EditRequest body) {
        return takes.edit(user.id(), id, eventId, body.text(), body.isPrivate());
    }

    @GetMapping
    List<TakeService.Take> list(@AuthenticationPrincipal NovaUser user, @RequestParam(required = false) LocalDate date) {
        return takes.forDate(user.id(), date == null ? time.today(user.id()) : date);
    }

    @GetMapping("/{id}")
    TakeService.TakeDetail detail(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return takes.detail(user.id(), id);
    }

    @GetMapping("/export")
    ResponseEntity<?> export(@AuthenticationPrincipal NovaUser user, @RequestParam LocalDate date,
                             @RequestParam(defaultValue = "json") String format,
                             @RequestParam(required = false) List<UUID> takeIds) {
        var data = takes.exportable(user.id(), date, takeIds);
        var name = "nova-ujecia-" + date + ("csv".equals(format) ? ".csv" : ".json");
        var headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        if ("csv".equals(format)) {
            headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
            return ResponseEntity.ok().headers(headers).body(TakeExport.csv(data));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("date", date.toString());
        body.put("timeZone", time.zone(user.id()).getId());
        body.put("exportedAt", time.now().toString());
        body.put("takes", data.stream().map(t -> Map.of("take", t.take(), "events", t.events())).toList());
        body.put("rows", TakeExport.rows(data));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
