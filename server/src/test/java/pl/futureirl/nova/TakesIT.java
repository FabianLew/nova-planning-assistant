package pl.futureirl.nova;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** M5 acceptance (spec p.10): takes, pause, network loss, 5 markers, privacy, JSON/CSV export. */
@IntegrationTest
class TakesIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @Autowired TestSupport.MutableClock clock;

    RequestPostProcessor auth;
    int n;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        db.sql("DELETE FROM take_events").update();
        db.sql("DELETE FROM takes").update();
        auth = TestSupport.login(mvc);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Europe/Warsaw"));
    }

    JsonNode send(MockHttpServletRequestBuilder req, Object body, ResultMatcher expect) throws Exception {
        req = req.with(auth).with(csrf());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var res = mvc.perform(req).andExpect(expect).andReturn().getResponse().getContentAsString();
        return res.isEmpty() ? null : json.readTree(res);
    }

    String start(String label) throws Exception {
        return send(post("/api/takes"), Map.of("label", label), status().isOk()).get("id").asText();
    }

    JsonNode log(String takeId, String type, String speaker, String text, ResultMatcher expect) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("clientEventId", "c" + (++n));
        body.put("type", type);
        body.put("speaker", speaker);
        body.put("text", text);
        body.put("occurredAtMs", clock.instant().toEpochMilli());
        return send(post("/api/takes/" + takeId + "/events"), body, expect);
    }

    JsonNode exportJson() throws Exception {
        return send(get("/api/takes/export").param("date", today().toString()), null, status().isOk());
    }

    @Test
    void setTestScenario_threeTakes_pause_networkLoss_fiveMarkers_exportInOrder() throws Exception {
        var t1 = start("Poranek");
        clock.advance(Duration.ofMillis(12_400));
        log(t1, "utterance", "creator", "Zaplanuj mi dzień", status().isOk());
        clock.advance(Duration.ofSeconds(3));
        log(t1, "utterance", "assistant", "Proponuję trzy bloki", status().isOk());
        log(t1, "marker", "creator", null, status().isOk());
        send(post("/api/takes/" + t1 + "/pause"), null, status().isOk());
        clock.advance(Duration.ofSeconds(20));
        log(t1, "utterance", "creator", "To nie trafia do zapisu", status().isConflict());
        send(post("/api/takes/" + t1 + "/resume"), null, status().isOk());
        clock.advance(Duration.ofSeconds(1));
        log(t1, "marker", "creator", "Dobra reakcja", status().isOk());
        send(post("/api/takes/" + t1 + "/stop"), null, status().isOk());

        var t2 = start("Utrata sieci");
        clock.advance(Duration.ofSeconds(5));
        log(t2, "connection_lost", null, "Brak sieci", status().isOk());
        clock.advance(Duration.ofSeconds(4));
        log(t2, "gap", null, "Przerwa 4 s", status().isOk());
        log(t2, "connection_restored", null, "Połączenie wróciło", status().isOk());
        log(t2, "marker", "creator", null, status().isOk());
        log(t2, "interruption", "creator", "Przerwanie asystenta", status().isOk());

        // starting the third take closes the second one automatically
        var t3 = start("Wieczór");
        assertThat(send(get("/api/takes/" + t2), null, status().isOk()).get("take").get("status").asText()).isEqualTo("closed");
        clock.advance(Duration.ofSeconds(2));
        log(t3, "decision", "creator", "Akceptuję plan", status().isOk());
        log(t3, "marker", "creator", "Puenta", status().isOk());
        clock.advance(Duration.ofSeconds(1));
        log(t3, "marker", "creator", null, status().isOk());
        send(post("/api/takes/" + t3 + "/stop"), null, status().isOk());

        var takes = send(get("/api/takes").param("date", today().toString()), null, status().isOk());
        assertThat(takes).hasSize(3);
        assertThat(takes.get(0).get("number").asInt()).isEqualTo(1);
        assertThat(takes.get(2).get("number").asInt()).isEqualTo(3);

        var export = exportJson();
        var rows = export.get("rows");
        var markers = new ArrayList<JsonNode>();
        rows.forEach(r -> { if (r.get("type").asText().equals("marker")) markers.add(r); });
        assertThat(markers).hasSize(5);
        assertThat(markers).allSatisfy(m -> assertThat(m.get("offset").asText()).matches("\\d{2}:\\d{2}\\.\\d{3}"));

        // first utterance at 00:12.400 from the take's zero point
        var first = rows.get(1);
        assertThat(first.get("type").asText()).isEqualTo("utterance");
        assertThat(first.get("offset").asText()).isEqualTo("00:12.400");

        // order: by take, then seq; pause and resume stay in as a gap; blocked utterance is absent
        int lastTake = 0, lastSeq = 0;
        var types = new ArrayList<String>();
        for (var r : rows) {
            int take = r.get("take").asInt(), seq = r.get("seq").asInt();
            assertThat(take > lastTake || (take == lastTake && seq > lastSeq)).isTrue();
            lastTake = take;
            lastSeq = seq;
            if (take == 1) types.add(r.get("type").asText());
            assertThat(r.get("text").asText()).doesNotContain("nie trafia do zapisu");
        }
        assertThat(types).containsExactly("sync", "utterance", "utterance", "marker", "pause", "resume", "marker", "stop");
        assertThat(String.join(",", rowsTypes(rows, 2))).contains("connection_lost,gap,connection_restored");
    }

    static List<String> rowsTypes(JsonNode rows, int take) {
        var out = new ArrayList<String>();
        rows.forEach(r -> { if (r.get("take").asInt() == take) out.add(r.get("type").asText()); });
        return out;
    }

    @Test
    void privacyPausesAndPrivateEventsStayOutOfExport() throws Exception {
        var t = start("Prywatne");
        var secret = log(t, "utterance", "creator", "Numer konta to 1234", status().isOk());
        log(t, "utterance", "assistant", "Zapisane", status().isOk());
        send(patch("/api/takes/" + t + "/events/" + secret.get("id").asText()), Map.of("isPrivate", true), status().isOk());
        var paused = send(post("/api/takes/" + t + "/privacy"), null, status().isOk());
        assertThat(paused.get("status").asText()).isEqualTo("paused");
        log(t, "utterance", "creator", "Rozmowa prywatna", status().isConflict());

        var export = exportJson().toString();
        assertThat(export).doesNotContain("1234").doesNotContain("Rozmowa prywatna").contains("Zapisane").contains("privacy");
        // still visible in the take detail for the creator, flagged private
        var detail = send(get("/api/takes/" + t), null, status().isOk());
        assertThat(detail.toString()).contains("1234");
    }

    @Test
    void clientEventIdIsIdempotent_andServerOnlyTypesAreRejected() throws Exception {
        var t = start("Retry");
        var body = Map.of("clientEventId", "same-1", "type", "marker", "speaker", "creator");
        var a = send(post("/api/takes/" + t + "/events"), body, status().isOk());
        var b = send(post("/api/takes/" + t + "/events"), body, status().isOk());
        assertThat(a.get("id")).isEqualTo(b.get("id"));
        assertThat(db.sql("SELECT count(*) FROM take_events WHERE take_id = ?::uuid AND type = 'marker'").param(t).query(Integer.class).single())
                .isEqualTo(1);
        send(post("/api/takes/" + t + "/events"), Map.of("type", "stop"), status().isBadRequest());
        send(post("/api/takes/" + t + "/events"), Map.of("type", "privacy"), status().isBadRequest());
    }

    @Test
    void farOffPhoneClockFallsBackToServerTime() throws Exception {
        var t = start("Zegar");
        clock.advance(Duration.ofSeconds(10));
        var e = send(post("/api/takes/" + t + "/events"),
                Map.of("type", "marker", "occurredAtMs", clock.instant().minus(Duration.ofHours(2)).toEpochMilli()), status().isOk());
        assertThat(e.get("offsetMs").asLong()).isEqualTo(10_000);
    }

    @Test
    void csvExportHasHeaderBomAndSafeCells() throws Exception {
        var t = start("CSV");
        log(t, "utterance", "creator", "=HYPERLINK(\"x\") i \"cytat\", przecinek", status().isOk());
        log(t, "marker", "creator", "Ujęcie na intro", status().isOk());
        var res = mvc.perform(get("/api/takes/export").param("date", today().toString()).param("format", "csv").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getHeader("Content-Disposition")).contains("nova-ujecia-" + today() + ".csv");
        var body = new String(res.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿take,take_id,event_id,seq,time_utc,offset,offset_ms,type,speaker,text\r\n");
        assertThat(body).contains("\"'=HYPERLINK(\"\"x\"\") i \"\"cytat\"\", przecinek\"").contains("Ujęcie na intro");
        assertThat(body.split("\r\n")).hasSize(1 + 3);
    }

    @Test
    void onlyOneOpenTake_andOwnerIsolation() throws Exception {
        var a = start("A");
        var b = start("B");
        assertThat(send(get("/api/takes/open"), null, status().isOk()).get("id").asText()).isEqualTo(b);
        assertThat(send(get("/api/takes/" + a), null, status().isOk()).get("take").get("status").asText()).isEqualTo("closed");
        send(post("/api/takes/" + b + "/stop"), null, status().isOk());
        send(get("/api/takes/open"), null, status().isNoContent());
        log(b, "marker", "creator", null, status().isConflict());

        var other = UUID.randomUUID();
        var otherTake = UUID.randomUUID();
        db.sql("INSERT INTO users (id, username, password_hash) VALUES (?, ?, 'x')").params(other, "intruz-" + other).update();
        db.sql("INSERT INTO takes (id, owner_id, take_date, number, started_at) VALUES (?, ?, ?, 1, now())")
                .params(otherTake, other, today()).update();
        send(get("/api/takes/" + otherTake), null, status().isNotFound());
        log(otherTake.toString(), "marker", "creator", null, status().isNotFound());
        send(post("/api/takes/" + otherTake + "/stop"), null, status().isNotFound());
        assertThat(exportJson().get("takes")).hasSize(2);
        db.sql("DELETE FROM take_events WHERE owner_id = ?").param(other).update();
        db.sql("DELETE FROM takes WHERE owner_id = ?").param(other).update();
        db.sql("DELETE FROM users WHERE id = ?").param(other).update();
    }
}
