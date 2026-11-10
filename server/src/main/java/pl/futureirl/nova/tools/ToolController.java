package pl.futureirl.nova.tools;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.futureirl.nova.security.NovaUser;

/**
 * Authenticated adapter for function calls (spec p.2 allows it instead of a server-side provider channel):
 * the phone forwards the model's call verbatim and returns the server's output to the model.
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/tool-calls")
public class ToolController {
    private final ToolService tools;

    public ToolController(ToolService tools) {
        this.tools = tools;
    }

    public record ToolCallRequest(@NotBlank @Size(max = 200) String callId,
                                  @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z_]+") String name,
                                  @Size(max = 20000) String arguments) {}

    @PostMapping
    ToolService.ToolOutcome call(@AuthenticationPrincipal NovaUser user, @PathVariable UUID sessionId,
                                 @Valid @RequestBody ToolCallRequest body) {
        return tools.execute(user.id(), sessionId, body.callId(), body.name(), body.arguments());
    }
}
