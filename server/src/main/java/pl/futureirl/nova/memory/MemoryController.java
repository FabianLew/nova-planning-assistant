package pl.futureirl.nova.memory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.futureirl.nova.security.NovaUser;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    private final MemoryService service;

    public MemoryController(MemoryService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank @Size(max = 500) String content, @Min(1) @Max(5) Integer importance) {}

    public record EditRequest(@Size(min = 1, max = 500) String content, @Min(1) @Max(5) Integer importance, int expectedVersion) {}

    @GetMapping
    List<Memory> list(@AuthenticationPrincipal NovaUser user, @RequestParam(required = false) String status) {
        return service.list(user.id(), status);
    }

    /** A fact the creator types in the Memory screen is explicit, so it is confirmed at once. */
    @PostMapping
    Memory create(@AuthenticationPrincipal NovaUser user, @Valid @RequestBody CreateRequest body) {
        return service.create(user.id(), "fact", body.content(), true, body.importance() == null ? 3 : body.importance(), null, "Ekran Pamięć");
    }

    @PutMapping("/{id}")
    Memory edit(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id, @Valid @RequestBody EditRequest body) {
        return service.edit(user.id(), id, body.content(), body.importance(), body.expectedVersion());
    }

    @PostMapping("/{id}/confirm")
    Memory confirm(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return service.confirm(user.id(), id);
    }

    @PostMapping("/{id}/reject")
    Memory reject(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        return service.reject(user.id(), id);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal NovaUser user, @PathVariable UUID id) {
        service.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
