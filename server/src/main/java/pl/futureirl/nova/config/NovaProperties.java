package pl.futureirl.nova.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nova")
public record NovaProperties(Auth auth, List<String> allowedOrigins, OpenAi openai, Limits limits) {

    public record Auth(String username, String password, String timeZone) {}

    public record OpenAi(String apiKey, String baseUrl, String model, String transcriptionModel, String defaultVoice) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Limits(int maxSessionMinutes, int maxStartsPerHour) {}

    public NovaProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
    }
}
