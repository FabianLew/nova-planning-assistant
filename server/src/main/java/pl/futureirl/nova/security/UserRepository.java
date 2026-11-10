package pl.futureirl.nova.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcClient db;

    public UserRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<NovaUser> findByUsername(String username) {
        return db.sql("SELECT id, username, password_hash FROM users WHERE username = ?")
                .param(username)
                .query((rs, i) -> new NovaUser(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash")))
                .optional();
    }

    public String timeZone(UUID ownerId) {
        return db.sql("SELECT time_zone FROM users WHERE id = ?").param(ownerId).query(String.class).single();
    }

    /** Creates the creator account or refreshes its password hash and time zone. Returns the user id. */
    public UUID upsert(String username, String passwordHash, String timeZone) {
        return db.sql("""
                INSERT INTO users (id, username, password_hash, time_zone) VALUES (?, ?, ?, ?)
                ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash, time_zone = EXCLUDED.time_zone
                RETURNING id
                """)
                .params(UUID.randomUUID(), username, passwordHash, timeZone)
                .query(UUID.class).single();
    }
}
