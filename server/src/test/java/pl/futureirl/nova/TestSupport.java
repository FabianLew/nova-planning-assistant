package pl.futureirl.nova;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import pl.futureirl.nova.realtime.RealtimeProvider;

public final class TestSupport {
    private TestSupport() {}

    /** Logs in and returns a post-processor that sends the Spring Session cookie on later requests. */
    public static RequestPostProcessor login(MockMvc mvc) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"fabian\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        var cookie = result.getResponse().getCookie("NOVA_SESSION");
        if (cookie == null) {
            throw new AssertionError("login did not set NOVA_SESSION");
        }
        return request -> {
            request.setCookies(cookie);
            return request;
        };
    }

    /** Records the last spec and never calls the network. */
    public static class FakeProvider implements RealtimeProvider {
        public volatile SessionSpec lastSpec;
        public final AtomicInteger calls = new AtomicInteger();

        @Override public String name() { return "fake"; }
        @Override public String model() { return "fake-realtime"; }

        @Override
        public ClientSecret createClientSecret(SessionSpec spec) {
            lastSpec = spec;
            calls.incrementAndGet();
            return new ClientSecret("ek_test_" + calls.get(), Instant.now().plusSeconds(60), "https://example.test/v1/realtime/calls");
        }
    }

    @TestConfiguration
    public static class Config {
        @Bean
        FlywayMigrationStrategy cleanMigrate() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
            };
        }

        @Bean
        @Primary
        FakeProvider fakeProvider() {
            return new FakeProvider();
        }
    }
}
