package pl.futureirl.nova.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.futureirl.nova.common.ApiException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;
    private final LoginThrottle throttle;

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contextRepository, LoginThrottle throttle) {
        this.authManager = authManager;
        this.contextRepository = contextRepository;
        this.throttle = throttle;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record Me(String id, String username) {}

    /** Primes the XSRF-TOKEN cookie for the SPA. */
    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        token.getToken(); // loading the deferred token writes the cookie
        return Map.of("headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (throttle.blocked()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "login_throttled", "Too many failed attempts, try again later");
        }
        try {
            var auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            throttle.succeeded();
            request.getSession(true);
            request.changeSessionId();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
            var user = (NovaUser) auth.getPrincipal();
            return new Me(user.id().toString(), user.getUsername());
        } catch (AuthenticationException e) {
            throttle.failed();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Wrong username or password");
        }
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal NovaUser user) {
        return new Me(user.id().toString(), user.getUsername());
    }
}
