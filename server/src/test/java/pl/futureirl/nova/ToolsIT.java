package pl.futureirl.nova;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** M2 and M3 acceptance criteria from spec p.9, exercised through the HTTP API. */
@IntegrationTest
class ToolsIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @Autowired TestSupport.FakeProvider provider;
    @Autowired TestSupport.MutableClock clock;

    RequestPostProcessor auth;
    String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        for (var t : new String[] {"tool_executions", "tasks", "approvals", "plan_changes", "daily_plans", "busy_blocks",
                "memory_audit", "memories", "owner_events", "sessions"}) {
            db.sql("DELETE FROM " + t).update();
        }
        auth = TestSupport.login(mvc);
        newSession();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    /** Opening a session supersedes the previous one, so later tool calls go to the new one. */
    String newSession() throws Exception {
        sessionId = send(post("/api/sessions"), null, status().isOk()).get("sessionId").asText();
        return sessionId;
    }

    JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object body, ResultMatcher expect) throws Exception {
        req = req.with(auth).with(csrf());
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var res = mvc.perform(req).andExpect(expect).andReturn().getResponse().getContentAsString();
        return res.isEmpty() ? null : json.readTree(res);
    }

    JsonNode tool(String callId, String name, String args) throws Exception {
        return tool(sessionId, callId, name, args);
    }

    JsonNode tool(String session, String callId, String name, String args) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("callId", callId);
        body.put("name", name);
        body.put("arguments", args);
        return send(post("/api/sessions/" + session + "/tool-calls"), body, status().isOk());
    }

    JsonNode decide(String approvalId, String decision, String key, ResultMatcher expect) throws Exception {
        return send(post("/api/approvals/" + approvalId + "/decision"), java.util.Map.of("decision", decision, "key", key), expect);
    }

    JsonNode today() throws Exception {
        return send(get("/api/days/today"), null, status().isOk());
    }

    int count(String sql) {
        return db.sql(sql).query(Integer.class).single();
    }

    // ------------------------------------------------------------------ M2: memory and actions

    @Test
    void savedPreferenceIsKnownInNewSessionThenCorrectedThenForgotten() throws Exception {
        var r = tool("c1", "save_memory", "{\"content\":\"Pracuję do 17\",\"explicit\":true,\"sensitive\":false,\"importance\":4}");
        assertThat(r.at("/output/memoryStatus").asText()).isEqualTo("confirmed");
        var memoryId = r.at("/output/memoryId").asText();

        newSession();
        assertThat(provider.lastSpec.instructions()).contains("Pracuję do 17");

        var m = send(put("/api/memories/" + memoryId), java.util.Map.of("content", "Pracuję do 18", "expectedVersion", 1), status().isOk());
        assertThat(m.get("version").asInt()).isEqualTo(2);
        newSession();
        assertThat(provider.lastSpec.instructions()).contains("Pracuję do 18").doesNotContain("Pracuję do 17");

        send(delete("/api/memories/" + memoryId), null, status().isNoContent());
        newSession();
        assertThat(provider.lastSpec.instructions()).doesNotContain("Pracuję do 18");
        // audit keeps the id and operation, never the text
        assertThat(count("SELECT count(*) FROM memory_audit WHERE memory_id = '" + memoryId + "' AND operation = 'delete'")).isEqualTo(1);
        assertThat(db.sql("SELECT content FROM memories WHERE id = ?").param(UUID.fromString(memoryId)).query(String.class).single()).isEmpty();
    }

    @Test
    void guessesAwaitConfirmationAndSensitiveInfoIsNotStored() throws Exception {
        var guess = tool("c1", "save_memory", "{\"content\":\"Woli spacer po montażu\",\"explicit\":false,\"sensitive\":false}");
        assertThat(guess.at("/output/memoryStatus").asText()).isEqualTo("pending");
        newSession();
        assertThat(provider.lastSpec.instructions()).doesNotContain("Woli spacer");

        send(post("/api/memories/" + guess.at("/output/memoryId").asText() + "/confirm"), null, status().isOk());
        newSession();
        assertThat(provider.lastSpec.instructions()).contains("Woli spacer po montażu");

        var sensitive = tool("c2", "save_memory", "{\"content\":\"Bierze leki na serce\",\"explicit\":true,\"sensitive\":true}");
        assertThat(sensitive.get("status").asText()).isEqualTo("skipped");
        assertThat(count("SELECT count(*) FROM memories WHERE content LIKE '%leki%'")).isZero();
    }

    @Test
    void contextHasAtMostFiveMemories() throws Exception {
        for (int i = 0; i < 8; i++) {
            tool("m" + i, "save_memory", "{\"content\":\"Fakt numer " + i + "\",\"explicit\":true,\"sensitive\":false}");
        }
        newSession();
        var matches = provider.lastSpec.instructions().split("Fakt numer", -1).length - 1;
        assertThat(matches).isEqualTo(5);
    }

    @Test
    void twoIdenticalCreateTaskCallsGiveOneRecord() throws Exception {
        var args = "{\"title\":\"Montaż vloga\",\"startTime\":\"09:00\",\"durationMin\":120}";
        var first = tool("call_A", "create_task", args);
        var retry = tool("call_A", "create_task", args);
        assertThat(first.get("status").asText()).isEqualTo("ok");
        assertThat(retry.get("replayed").asBoolean()).isTrue();
        assertThat(retry.at("/output/taskId").asText()).isEqualTo(first.at("/output/taskId").asText());
        assertThat(count("SELECT count(*) FROM tasks")).isEqualTo(1);
        assertThat(today().get("tasks").size()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesOfOneCallStillGiveOneRecord() throws Exception {
        var args = "{\"title\":\"Spacer\",\"startTime\":\"11:00\"}";
        Callable<JsonNode> call = () -> tool("call_B", "create_task", args);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = pool.invokeAll(java.util.List.of(call, call, call, call));
            for (var f : futures) assertThat(f.get().at("/output/title").asText()).isEqualTo("Spacer");
        }
        assertThat(count("SELECT count(*) FROM tasks")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tool_executions")).isEqualTo(1);
    }

    @Test
    void invalidArgumentsAreRejectedWithoutSideEffects() throws Exception {
        var r = tool("c1", "create_task", "{\"title\":\"X\",\"startTime\":\"25:99\"}");
        assertThat(r.get("status").asText()).isEqualTo("error");
        assertThat(r.at("/output/error").asText()).isEqualTo("invalid_arguments");
        var extra = tool("c2", "create_task", "{\"title\":\"X\",\"dropTables\":true}");
        assertThat(extra.at("/output/error").asText()).isEqualTo("invalid_arguments");
        var unknown = tool("c3", "send_payment", "{}");
        assertThat(unknown.at("/output/error").asText()).isEqualTo("unknown_tool");
        assertThat(count("SELECT count(*) FROM tasks")).isZero();
    }

    @Test
    void anotherOwnersSessionIsRefused() throws Exception {
        var other = UUID.randomUUID();
        var foreignSession = UUID.randomUUID();
        db.sql("INSERT INTO users (id, username, password_hash) VALUES (?, 'intruder2', 'x')").param(other).update();
        db.sql("INSERT INTO sessions (id, owner_id, provider, model, voice, status) VALUES (?, ?, 'fake', 'm', 'v', 'active')")
                .params(foreignSession, other).update();
        var body = java.util.Map.of("callId", "x", "name", "get_current_context", "arguments", "{}");
        send(post("/api/sessions/" + foreignSession + "/tool-calls"), body, status().isNotFound());
        db.sql("DELETE FROM sessions WHERE id = ?").param(foreignSession).update();
        db.sql("DELETE FROM users WHERE id = ?").param(other).update();
    }

    // ------------------------------------------------------------------ M3: plan and approvals

    String proposePlan(String callId) throws Exception {
        var r = tool(callId, "propose_daily_plan", """
                {"items":[
                  {"title":"Montaż vloga","startTime":"09:00","durationMin":480,"reason":"Termin publikacji"},
                  {"title":"Wspinaczka","startTime":"17:00","durationMin":90,"costPln":80,"reason":"Ruch po montażu"}
                ]}""");
        assertThat(r.get("status").asText()).isEqualTo("pending_approval");
        return r.at("/output/approvalId").asText();
    }

    @Test
    void proposedPlanAppliesOnlyAfterTapAndExactlyOnce() throws Exception {
        var approvalId = proposePlan("p1");
        var before = today();
        assertThat(before.at("/plan/status").asText()).isEqualTo("draft");
        assertThat(before.get("tasks").findValuesAsText("status")).containsOnly("proposed");

        var d1 = decide(approvalId, "approve", "tap-1", status().isOk());
        assertThat(d1.get("outcome").asText()).isEqualTo("approved");
        var again = decide(approvalId, "approve", "tap-1", status().isOk()); // double tap, same key
        assertThat(again.get("outcome").asText()).isEqualTo("approved");
        decide(approvalId, "approve", "other-device", status().isConflict());

        var after = today();
        assertThat(after.at("/plan/status").asText()).isEqualTo("approved");
        assertThat(after.at("/plan/version").asInt()).isEqualTo(2);
        assertThat(after.get("tasks").findValuesAsText("status")).containsOnly("planned");
        assertThat(count("SELECT count(*) FROM plan_changes")).isEqualTo(1);
    }

    @Test
    void conflictCardMovesTaskOnlyWhenApproved() throws Exception {
        decide(proposePlan("p1"), "approve", "k1", status().isOk());
        var day = today();
        var planId = day.at("/plan/id").asText();
        var climbing = taskId(day, "Wspinaczka");
        // the creator says editing runs until 17:30
        tool("b1", "add_busy_block", "{\"startTime\":\"09:00\",\"endTime\":\"17:30\",\"label\":\"Montaż\"}");

        var card = tool("u1", "update_daily_plan",
                "{\"planId\":\"" + planId + "\",\"expectedVersion\":2,\"taskId\":\"" + climbing + "\",\"startTime\":\"18:00\"}");
        assertThat(card.get("status").asText()).isEqualTo("pending_approval");
        assertThat(card.at("/output/summary").asText()).contains("17:00 → 18:00");
        assertThat(card.at("/output/conflict").asText()).contains("Montaż 09:00–17:30");
        assertThat(startOf(today(), "Wspinaczka")).isEqualTo("17:00");

        decide(card.at("/output/approvalId").asText(), "approve", "k2", status().isOk());
        assertThat(startOf(today(), "Wspinaczka")).isEqualTo("18:00");
    }

    @Test
    void rejectionAndExpiryLeaveThePlanUnchanged() throws Exception {
        decide(proposePlan("p1"), "approve", "k1", status().isOk());
        var day = today();
        var planId = day.at("/plan/id").asText();
        var climbing = taskId(day, "Wspinaczka");
        var args = "{\"planId\":\"" + planId + "\",\"expectedVersion\":2,\"taskId\":\"" + climbing + "\",\"startTime\":\"19:00\"}";

        var rejected = tool("u1", "update_daily_plan", args).at("/output/approvalId").asText();
        assertThat(decide(rejected, "reject", "k2", status().isOk()).get("outcome").asText()).isEqualTo("rejected");

        var expiring = tool("u2", "update_daily_plan", args).at("/output/approvalId").asText();
        clock.advance(Duration.ofMinutes(16));
        assertThat(decide(expiring, "approve", "k3", status().isOk()).get("outcome").asText()).isEqualTo("expired");

        assertThat(startOf(today(), "Wspinaczka")).isEqualTo("17:00");
        assertThat(today().at("/plan/version").asInt()).isEqualTo(2);
    }

    @Test
    void competingCardsChangeThePlanOnlyOnce() throws Exception {
        decide(proposePlan("p1"), "approve", "k1", status().isOk());
        var day = today();
        var planId = day.at("/plan/id").asText();
        var climbing = taskId(day, "Wspinaczka");
        var a = tool("u1", "update_daily_plan", "{\"planId\":\"" + planId + "\",\"expectedVersion\":2,\"taskId\":\"" + climbing + "\",\"startTime\":\"18:00\"}")
                .at("/output/approvalId").asText();
        var b = tool("u2", "update_daily_plan", "{\"planId\":\"" + planId + "\",\"expectedVersion\":2,\"taskId\":\"" + climbing + "\",\"startTime\":\"19:30\"}")
                .at("/output/approvalId").asText();

        Callable<String> tapA = () -> decide(a, "approve", "ka", status().isOk()).get("outcome").asText();
        Callable<String> tapB = () -> decide(b, "approve", "kb", status().isOk()).get("outcome").asText();
        java.util.List<String> outcomes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            outcomes = pool.invokeAll(java.util.List.of(tapA, tapB)).stream().map(f -> {
                try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).toList();
        }
        assertThat(outcomes).containsExactlyInAnyOrder("approved", "superseded");
        assertThat(today().at("/plan/version").asInt()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM plan_changes")).isEqualTo(2); // accept + exactly one move
    }

    @Test
    void staleVersionIsRefusedSoTheModelRereadsThePlan() throws Exception {
        decide(proposePlan("p1"), "approve", "k1", status().isOk());
        var day = today();
        var r = tool("u1", "update_daily_plan", "{\"planId\":\"" + day.at("/plan/id").asText() + "\",\"expectedVersion\":1,\"taskId\":\""
                + taskId(day, "Wspinaczka") + "\",\"startTime\":\"18:00\"}");
        assertThat(r.at("/output/error").asText()).isEqualTo("stale_version");
        assertThat(count("SELECT count(*) FROM approvals WHERE kind = 'plan_change'")).isZero();
    }

    @Test
    void refusalIsRecordedAsEpisode() throws Exception {
        decide(proposePlan("p1"), "approve", "k1", status().isOk());
        var climbing = taskId(today(), "Wspinaczka");
        tool("s1", "set_task_status", "{\"taskId\":\"" + climbing + "\",\"status\":\"refused\",\"reason\":\"deszcz\"}");
        assertThat(db.sql("SELECT content FROM memories WHERE kind = 'episode'").query(String.class).single())
                .contains("Wspinaczka").contains("deszcz");
    }

    @Test
    void changesAreFedToTheEventStreamInOrder() throws Exception {
        proposePlan("p1");
        var events = send(get("/api/events?after=0"), null, status().isOk());
        var types = events.findValuesAsText("type");
        assertThat(types).contains("approval.requested", "tool.executed");
        var seqs = events.findValues("seq").stream().map(JsonNode::asLong).toList();
        assertThat(seqs).isSorted();
        var last = seqs.get(seqs.size() - 1);
        assertThat(send(get("/api/events?after=" + last), null, status().isOk()).size()).isZero();
    }

    private static String taskId(JsonNode day, String title) {
        for (var t : day.get("tasks")) if (t.get("title").asText().equals(title)) return t.get("id").asText();
        throw new AssertionError("no task " + title);
    }

    private String startOf(JsonNode day, String title) {
        for (var t : day.get("tasks")) {
            if (t.get("title").asText().equals(title)) {
                return java.time.Instant.parse(t.get("startAt").asText()).atZone(java.time.ZoneId.of("Europe/Warsaw"))
                        .toLocalTime().toString();
            }
        }
        throw new AssertionError("no task " + title);
    }
}
