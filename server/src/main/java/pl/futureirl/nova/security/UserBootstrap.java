package pl.futureirl.nova.security;

import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import pl.futureirl.nova.config.NovaProperties;
import pl.futureirl.nova.profile.ProfileRepository;

/** Creates the single creator account from NOVA_USERNAME / NOVA_PASSWORD on startup. */
@Component
public class UserBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final NovaProperties props;
    private final UserRepository users;
    private final ProfileRepository profiles;
    private final PasswordEncoder encoder;

    public UserBootstrap(NovaProperties props, UserRepository users, ProfileRepository profiles, PasswordEncoder encoder) {
        this.props = props;
        this.users = users;
        this.profiles = profiles;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        var auth = props.auth();
        if (auth.password() == null || auth.password().length() < 8) {
            log.warn("NOVA_PASSWORD is missing or shorter than 8 characters; login is disabled until it is set.");
            return;
        }
        ZoneId.of(auth.timeZone()); // fail fast on a bad zone id
        var id = users.upsert(auth.username(), encoder.encode(auth.password()), auth.timeZone());
        profiles.ensureDefault(id, props.openai().defaultVoice());
        log.info("Creator account '{}' is ready.", auth.username());
    }
}
