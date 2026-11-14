package pl.futureirl.nova.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention proposal from spec p.8: no raw audio is stored at all; take and session transcripts are kept
 * 30 days, technical diagnostics 7 days. Deleting earlier by hand stays possible.
 */
@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcClient db;
    private final int transcriptDays;
    private final int diagnosticsDays;

    public RetentionJob(JdbcClient db, @Value("${nova.retention.transcript-days:30}") int transcriptDays,
                        @Value("${nova.retention.diagnostics-days:7}") int diagnosticsDays) {
        this.db = db;
        this.transcriptDays = transcriptDays;
        this.diagnosticsDays = diagnosticsDays;
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purge() {
        int takeEvents = db.sql("DELETE FROM take_events WHERE occurred_at < now() - make_interval(days => ?)").param(transcriptDays).update();
        int messages = db.sql("DELETE FROM messages WHERE created_at < now() - make_interval(days => ?)").param(transcriptDays).update();
        int diagnostics = db.sql("DELETE FROM events WHERE occurred_at < now() - make_interval(days => ?)").param(diagnosticsDays).update();
        int feed = db.sql("DELETE FROM owner_events WHERE occurred_at < now() - make_interval(days => ?)").param(diagnosticsDays).update();
        int tools = db.sql("DELETE FROM tool_executions WHERE created_at < now() - make_interval(days => ?)").param(transcriptDays).update();
        log.info("Retention: removed {} take events, {} messages, {} diagnostics, {} feed rows, {} tool rows",
                takeEvents, messages, diagnostics, feed, tools);
    }
}
