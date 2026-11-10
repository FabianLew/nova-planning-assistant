package pl.futureirl.nova.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pl.futureirl.nova.common.ApiException;
import pl.futureirl.nova.config.NovaProperties;

class OpenAiRealtimeProviderTest {
    static NovaProperties props(String key) {
        return new NovaProperties(new NovaProperties.Auth("u", "p", "Europe/Warsaw"), List.of(),
                new NovaProperties.OpenAi(key, "https://api.openai.test", "gpt-realtime", "gpt-4o-transcribe", "marin"),
                new NovaProperties.Limits(20, 30, 100));
    }

    static final RealtimeProvider.SessionSpec SPEC =
            new RealtimeProvider.SessionSpec("Bądź NOVĄ", "marin", "server_vad", 650, "auto", 120, pl.futureirl.nova.tools.ToolDefinitions.ALL);

    @Test
    void mintsClientSecretWithServerKeyAndPolishSessionConfig() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.test/v1/realtime/client_secrets"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-secret"))
                .andExpect(jsonPath("$.session.type").value("realtime"))
                .andExpect(jsonPath("$.session.model").value("gpt-realtime"))
                .andExpect(jsonPath("$.session.audio.input.transcription.language").value("pl"))
                .andExpect(jsonPath("$.session.audio.input.turn_detection.silence_duration_ms").value(650))
                .andExpect(jsonPath("$.session.audio.input.turn_detection.interrupt_response").value(true))
                .andExpect(jsonPath("$.session.audio.output.voice").value("marin"))
                .andExpect(jsonPath("$.expires_after.seconds").value(120))
                .andExpect(jsonPath("$.session.tools[0].type").value("function"))
                .andExpect(jsonPath("$.session.tool_choice").value("auto"))
                .andRespond(withSuccess("{\"value\":\"ek_abc\",\"expires_at\":1900000000,\"session\":{}}", MediaType.APPLICATION_JSON));

        var secret = new OpenAiRealtimeProvider(props("sk-secret"), builder).createClientSecret(SPEC);

        assertThat(secret.value()).isEqualTo("ek_abc");
        assertThat(secret.rtcUrl()).isEqualTo("https://api.openai.test/v1/realtime/calls");
        assertThat(secret.expiresAt().getEpochSecond()).isEqualTo(1900000000L);
        server.verify();
    }

    @Test
    void semanticVadSendsEagernessInsteadOfSilence() {
        var provider = new OpenAiRealtimeProvider(props("k"), RestClient.builder());
        var cfg = provider.sessionConfig(new RealtimeProvider.SessionSpec("x", "marin", "semantic_vad", 650, "low", 60, java.util.List.of()));
        assertThat(cfg.toString()).contains("eagerness=low").doesNotContain("silence_duration_ms");
    }

    @Test
    void providerErrorBecomesBadGatewayWithoutLeakingKey() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.test/v1/realtime/client_secrets")).andRespond(withServerError());
        var provider = new OpenAiRealtimeProvider(props("sk-secret"), builder);
        assertThatThrownBy(() -> provider.createClientSecret(SPEC))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining("sk-secret");
    }

    @Test
    void missingKeyIsReportedClearly() {
        var provider = new OpenAiRealtimeProvider(props(""), RestClient.builder());
        assertThatThrownBy(() -> provider.createClientSecret(SPEC))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("provider_not_configured"));
    }
}
