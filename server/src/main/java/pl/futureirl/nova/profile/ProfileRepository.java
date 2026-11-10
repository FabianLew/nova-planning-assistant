package pl.futureirl.nova.profile;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pl.futureirl.nova.common.ApiException;

@Repository
public class ProfileRepository {
    private final JdbcClient db;

    public ProfileRepository(JdbcClient db) {
        this.db = db;
    }

    public void ensureDefault(UUID ownerId, String voice) {
        db.sql("""
                INSERT INTO assistant_profiles (owner_id, display_name, voice, instructions)
                VALUES (?, ?, ?, ?) ON CONFLICT (owner_id) DO NOTHING
                """)
                .params(ownerId, DefaultPersona.DISPLAY_NAME, voice, DefaultPersona.INSTRUCTIONS)
                .update();
    }

    public Profile get(UUID ownerId) {
        return db.sql("""
                SELECT display_name, voice, instructions, turn_detection, vad_silence_ms, vad_eagerness, version
                FROM assistant_profiles WHERE owner_id = ?
                """)
                .param(ownerId)
                .query((rs, i) -> new Profile(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getString(6), rs.getInt(7)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("profile"));
    }

    /** Optimistic update: the caller sends the version it edited. */
    public Profile update(UUID ownerId, Profile p) {
        int n = db.sql("""
                UPDATE assistant_profiles
                SET display_name = ?, voice = ?, instructions = ?, turn_detection = ?, vad_silence_ms = ?,
                    vad_eagerness = ?, version = version + 1, updated_at = now()
                WHERE owner_id = ? AND version = ?
                """)
                .params(p.displayName(), p.voice(), p.instructions(), p.turnDetection(), p.vadSilenceMs(),
                        p.vadEagerness(), ownerId, p.version())
                .update();
        if (n == 0) {
            throw ApiException.conflict("stale_version", "Profile was changed elsewhere, reload and try again");
        }
        return get(ownerId);
    }
}
