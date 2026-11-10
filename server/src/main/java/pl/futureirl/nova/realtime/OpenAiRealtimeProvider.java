package pl.futureirl.nova.realtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pl.futureirl.nova.common.ApiException;
import pl.futureirl.nova.config.NovaProperties;

/**
 * OpenAI Realtime over WebRTC. The server mints an ephemeral client secret; the permanent API key never
 * leaves the server (spec p.8). Docs: platform.openai.com/docs/guides/realtime-webrtc
 */
@Component
public class OpenAiRealtimeProvider implements RealtimeProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiRealtimeProvider.class);

    private final NovaProperties.OpenAi cfg;
    private final RestClient http;

    public OpenAiRealtimeProvider(NovaProperties props, RestClient.Builder builder) {
        this.cfg = props.openai();
        this.http = builder.baseUrl(cfg.baseUrl()).build();
    }

    @Override public String name() { return "openai"; }

    @Override public String model() { return cfg.model(); }

    @Override
    public ClientSecret createClientSecret(SessionSpec spec) {
        if (!cfg.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider_not_configured", "OPENAI_API_KEY is not set on the server");
        }
        var body = Map.of(
                "expires_after", Map.of("anchor", "created_at", "seconds", spec.ttlSeconds()),
                "session", sessionConfig(spec));
        try {
            var res = http.post().uri("/v1/realtime/client_secrets")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ClientSecretResponse.class);
            if (res == null || res.value() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "provider_error", "Empty response from provider");
            }
            var expires = res.expiresAt() > 0 ? Instant.ofEpochSecond(res.expiresAt()) : Instant.now().plusSeconds(spec.ttlSeconds());
            return new ClientSecret(res.value(), expires, cfg.baseUrl() + "/v1/realtime/calls");
        } catch (RestClientException e) {
            // Never log the request: it carries the key in a header. The message holds status and body only.
            log.warn("OpenAI client secret request failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "provider_error", "Voice provider refused to open a session");
        }
    }

    Map<String, Object> sessionConfig(SessionSpec spec) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("type", spec.turnDetection());
        if ("semantic_vad".equals(spec.turnDetection())) {
            turn.put("eagerness", spec.vadEagerness());
        } else {
            turn.put("threshold", 0.5);
            turn.put("prefix_padding_ms", 300);
            turn.put("silence_duration_ms", spec.vadSilenceMs());
        }
        // Barge-in: speech start cancels the running response; over WebRTC the provider also
        // truncates the assistant item to what was actually played.
        turn.put("create_response", true);
        turn.put("interrupt_response", true);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("type", "realtime");
        session.put("model", cfg.model());
        session.put("instructions", spec.instructions());
        session.put("output_modalities", List.of("audio"));
        if (spec.tools() != null && !spec.tools().isEmpty()) {
            session.put("tools", spec.tools());
            session.put("tool_choice", "auto");
        }
        session.put("audio", Map.of(
                "input", Map.of(
                        "transcription", Map.of("model", cfg.transcriptionModel(), "language", "pl"),
                        "noise_reduction", Map.of("type", "near_field"),
                        "turn_detection", turn),
                "output", Map.of("voice", spec.voice())));
        return session;
    }

    record ClientSecretResponse(String value, @com.fasterxml.jackson.annotation.JsonProperty("expires_at") long expiresAt) {}
}
