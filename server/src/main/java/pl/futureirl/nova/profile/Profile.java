package pl.futureirl.nova.profile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Profile(
        @NotBlank @Size(max = 40) String displayName,
        @NotBlank @Size(max = 40) @Pattern(regexp = "[a-z0-9_-]+") String voice,
        @NotBlank @Size(max = 8000) String instructions,
        @NotBlank @Pattern(regexp = "server_vad|semantic_vad") String turnDetection,
        @Min(200) @Max(3000) int vadSilenceMs,
        @NotBlank @Pattern(regexp = "low|medium|high|auto") String vadEagerness,
        int version) {}
