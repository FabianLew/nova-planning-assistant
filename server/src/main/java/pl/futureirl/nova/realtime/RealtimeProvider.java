package pl.futureirl.nova.realtime;

import java.time.Instant;

/** Opens a voice session at the model provider and returns a short-lived client credential. */
public interface RealtimeProvider {

    String name();

    String model();

    ClientSecret createClientSecret(SessionSpec spec);

    record SessionSpec(String instructions, String voice, String turnDetection, int vadSilenceMs, String vadEagerness,
                       int ttlSeconds) {}

    /** {@code rtcUrl} is where the browser posts its SDP offer, authorised with {@code value}. */
    record ClientSecret(String value, Instant expiresAt, String rtcUrl) {}
}
