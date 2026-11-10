package pl.futureirl.nova;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SessionIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @Autowired TestSupport.FakeProvider provider;

    RequestPostProcessor session;

    @BeforeEach
    void setUp() throws Exception {
        db.sql("DELETE FROM sessions").update();
        session = TestSupport.login(mvc);
    }

    JsonNode start() throws Exception {
        var res = mvc.perform(post("/api/sessions").with(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString());
    }

    JsonNode postJson(String url, String body) throws Exception {
        var res = mvc.perform(post(url).with(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void startReturnsEphemeralCredentialAndPersona() throws Exception {
        var s = start();
        assertThat(s.get("clientSecret").asText()).startsWith("ek_test_");
        assertThat(s.get("rtcUrl").asText()).endsWith("/v1/realtime/calls");
        assertThat(s.get("displayName").asText()).isEqualTo("NOVA");
        assertThat(s.get("maxDurationSeconds").asInt()).isPositive();
        assertThat(s.toString()).doesNotContain("sk-test");
        assertThat(provider.lastSpec.instructions()).contains("Nazywasz się NOVA").contains("Europe/Warsaw");
        assertThat(provider.lastSpec.voice()).isEqualTo("marin");
    }

    @Test
    void newSessionSupersedesOpenOne() throws Exception {
        var first = UUID.fromString(start().get("sessionId").asText());
        start();
        mvc.perform(get("/api/sessions/" + first).with(session))
                .andExpect(jsonPath("$.session.status").value("ended"))
                .andExpect(jsonPath("$.session.endReason").value("superseded"));
    }

    @Test
    void lifecycleConnectedThenEnded() throws Exception {
        var id = start().get("sessionId").asText();
        var connected = postJson("/api/sessions/" + id + "/connected", "{\"providerCallId\":\"rtc_123\"}");
        assertThat(connected.get("status").asText()).isEqualTo("active");
        assertThat(connected.get("providerCallId").asText()).isEqualTo("rtc_123");
        var ended = postJson("/api/sessions/" + id + "/end", "{\"reason\":\"user\"}");
        assertThat(ended.get("status").asText()).isEqualTo("ended");
        // a second end is harmless; reconnecting a closed session is not
        postJson("/api/sessions/" + id + "/end", "{}");
        mvc.perform(post("/api/sessions/" + id + "/connected").with(session).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void messagesAreOrderedAndIdempotent() throws Exception {
        var id = start().get("sessionId").asText();
        var url = "/api/sessions/" + id + "/messages";
        var a = postJson(url, "{\"role\":\"user\",\"content\":\"Po nocnym montażu?\",\"providerItemId\":\"item_1\"}");
        var again = postJson(url, "{\"role\":\"user\",\"content\":\"Po nocnym montażu?\",\"providerItemId\":\"item_1\"}");
        var b = postJson(url, "{\"role\":\"assistant\",\"content\":\"O siódmej bieg, potem\",\"interrupted\":true,\"providerItemId\":\"item_2\"}");
        assertThat(again.get("id")).isEqualTo(a.get("id"));
        assertThat(a.get("seq").asInt()).isEqualTo(1);
        assertThat(b.get("seq").asInt()).isEqualTo(2);
        mvc.perform(get("/api/sessions/" + id).with(session))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].interrupted").value(true));
    }

    @Test
    void metricsReportP95AgainstSpecTargets() throws Exception {
        var id = start().get("sessionId").asText();
        var url = "/api/sessions/" + id + "/events";
        for (int ms = 100; ms <= 2000; ms += 100) { // 20 samples
            postJson(url, "{\"type\":\"latency.reply\",\"payload\":{\"ms\":" + ms + ",\"vadSilenceMs\":0}}");
        }
        postJson(url, "{\"type\":\"latency.interrupt\",\"payload\":{\"ms\":250}}");
        mvc.perform(get("/api/sessions/" + id + "/metrics").with(session))
                .andExpect(jsonPath("$.replyFromSpeechEnd.count").value(20))
                .andExpect(jsonPath("$.replyFromSpeechEnd.p95").value(1900.0))
                .andExpect(jsonPath("$.replyFromSpeechEnd.meetsTarget").value(true))
                .andExpect(jsonPath("$.interrupt.meetsTarget").value(true));
    }

    @Test
    void badEventTypeIsRejected() throws Exception {
        var id = start().get("sessionId").asText();
        mvc.perform(post("/api/sessions/" + id + "/events").with(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DROP TABLE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherOwnerCannotSeeTheSession() throws Exception {
        var id = UUID.fromString(start().get("sessionId").asText());
        var other = UUID.randomUUID();
        db.sql("INSERT INTO users (id, username, password_hash) VALUES (?, 'intruder', 'x')").param(other).update();
        var visible = db.sql("SELECT count(*) FROM sessions WHERE id = ? AND owner_id = ?").params(id, other)
                .query(Integer.class).single();
        assertThat(visible).isZero();
        mvc.perform(get("/api/sessions/" + UUID.randomUUID()).with(session)).andExpect(status().isNotFound());
        db.sql("DELETE FROM users WHERE id = ?").param(other).update();
    }

    @Test
    void startLimitPerHour() throws Exception {
        for (int i = 0; i < 5; i++) start();
        mvc.perform(post("/api/sessions").with(session).with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too_many_sessions"));
    }
}
