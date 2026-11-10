package pl.futureirl.nova.profile;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.futureirl.nova.security.NovaUser;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final ProfileRepository profiles;

    public ProfileController(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    Profile get(@AuthenticationPrincipal NovaUser user) {
        return profiles.get(user.id());
    }

    @PutMapping
    Profile update(@AuthenticationPrincipal NovaUser user, @Valid @RequestBody Profile body) {
        return profiles.update(user.id(), body);
    }
}
