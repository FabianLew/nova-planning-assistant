package pl.futureirl.nova;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** M4 acceptance (spec p.9): seven simulated days, refusal, plan change, budget settlement, weekly report. */
@IntegrationTest
class ExperimentIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @Autowired TestSupport.FakeProvider provider;
    @Autowired TestSupport.MutableClock clock;

    RequestPostProcessor auth;
    String sessionId;
    String experimentId;
    int calls;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        for (var t : new String[] {"expenses", "reservations", "daily_reviews", "experiment_decisions", "experiments",
                "tool_executions", "tasks", "approvals", "plan_changes", "daily_plans", "busy_blocks", "memory_audit",
                "memories", "owner_events", "sessions"}) {
            db.sql("DELETE FROM " + t).update();
        }
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

    void startExperiment(int budgetPln, int apiLimitPln, List<String> exclusions) throws Exception {
        var cfg = new HashMap<String, Object>();
        cfg.put("goal", "Lepszy tydzień niż planuję sam");
        cfg.put("startDate", today().toString());
        cfg.put("workStart", "09:00");
        cfg.put("workEnd", "17:00");
        cfg.put("exclusions", exclusions);
        cfg.put("budgetPln", budgetPln);
        cfg.put("apiLimitPln", apiLimitPln);
        cfg.put("dayStart", "00:00");
        cfg.put("baselineRating", 3);
        cfg.put("baselineActivities", 2);
        experimentId = send(post("/api/experiments"), cfg, status().isOk()).get("id").asText();
        assertThat(send(get("/api/experiments/current"), null, status().isOk()).at("/experiment/status").asText()).isEqualTo("draft");
        send(post("/api/experiments/" + experimentId + "/transition"), Map.of("action", "activate"), status().isOk());
    }

    void newSession() throws Exception {
        sessionId = send(post("/api/sessions"), null, status().isOk()).get("sessionId").asText();
    }

    JsonNode tool(String name, String args) throws Exception {
        var body = Map.of("callId", "c" + (++calls), "name", name, "arguments", args);
        return send(post("/api/sessions/" + sessionId + "/tool-calls"), body, status().isOk());
    }

    JsonNode decide(String approvalId, ResultMatcher expect) throws Exception {
        return send(post("/api/approvals/" + approvalId + "/decision"), Map.of("decision", "approve", "key", "k" + (++calls)), expect);
    }

    JsonNode budget() throws Exception {
        return send(get("/api/experiments/current"), null, status().isOk()).get("budget");
    }

    String activity(String title, int pln) throws Exception {
        var r = tool("propose_activity", "{\"title\":\"" + title + "\",\"startTime\":\"18:00\",\"durationMin\":90,\"costPln\":" + pln + ",\"reason\":\"ruch\"}");
        assertThat(r.get("status").asText()).as(r.toString()).isEqualTo("pending_approval");
        return r.at("/output/approvalId").asText();
    }

    String taskId(String title) throws Exception {
        for (var t : send(get("/api/days/today"), null, status().isOk()).get("tasks")) {
            if (t.get("title").asText().equals(title)) return t.get("id").asText();
        }
        throw new AssertionError("no task " + title);
    }

    @Test
    void settlementExampleFromSpecGives125Free() throws Exception {
        startExperiment(200, 50, List.of());
        newSession();
        decide(activity("Wspinaczka", 80), status().isOk());
        assertThat(budget().get("availableGrosze").asLong()).isEqualTo(120_00);
        assertThat(budget().get("reservedGrosze").asLong()).isEqualTo(80_00);

        var r = tool("record_expense", "{\"amountPln\":75,\"description\":\"Rachunek za ściankę\",\"taskId\":\"" + taskId("Wspinaczka") + "\"}");
        assertThat(r.at("/output/available").asText()).isEqualTo("125,00 zł");
        assertThat(budget().get("availableGrosze").asLong()).isEqualTo(125_00);
        assertThat(budget().get("reservedGrosze").asLong()).isZero();
    }

    @Test
    void cancellingBeforePurchaseReleasesTheWholeReservation() throws Exception {
        startExperiment(200, 50, List.of());
        newSession();
        decide(activity("Basen", 50), status().isOk());
        assertThat(budget().get("availableGrosze").asLong()).isEqualTo(150_00);
        send(post("/api/tasks/" + taskId("Basen") + "/status"), Map.of("status", "refused", "reason", "zimno"), status().isOk());
        assertThat(budget().get("availableGrosze").asLong()).isEqualTo(200_00);
    }

    @Test
    void budgetGuardsPauseAndExclusions() throws Exception {
        startExperiment(100, 50, List.of("alkohol"));
        newSession();
        assertThat(tool("propose_activity", "{\"title\":\"Kolacja\",\"startTime\":\"19:00\",\"durationMin\":60,\"costPln\":500,\"reason\":\"x\"}")
                .at("/output/error").asText()).isEqualTo("insufficient_budget");
        assertThat(tool("propose_activity", "{\"title\":\"Degustacja, alkohol\",\"startTime\":\"19:00\",\"durationMin\":60,\"costPln\":0,\"reason\":\"x\"}")
                .at("/output/error").asText()).isEqualTo("excluded_activity");

        send(post("/api/experiments/" + experimentId + "/transition"), Map.of("action", "pause", "note", "choroba"), status().isOk());
        assertThat(tool("propose_activity", "{\"title\":\"Spacer\",\"startTime\":\"19:00\",\"durationMin\":30,\"costPln\":0,\"reason\":\"x\"}")
                .at("/output/error").asText()).isEqualTo("experiment_paused");
        send(post("/api/experiments/" + experimentId + "/transition"), Map.of("action", "resume"), status().isOk());

        // a real expense over the limit is still recorded, then blocks paid proposals; free ones still pass
        send(post("/api/experiments/current/expenses"), Map.of("amountPln", 130, "description", "Koncert"), status().isOk());
        assertThat(budget().get("overLimit").asBoolean()).isTrue();
        assertThat(budget().get("spentGrosze").asLong()).isEqualTo(130_00);
        assertThat(tool("propose_activity", "{\"title\":\"Kino\",\"startTime\":\"19:00\",\"durationMin\":60,\"costPln\":20,\"reason\":\"x\"}")
                .at("/output/error").asText()).isEqualTo("budget_exceeded");
        activity("Spacer", 0);
        assertThat(db.sql("SELECT count(*) FROM experiment_decisions").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void concurrentPaidApprovalsNeverOverdrawTheBudget() throws Exception {
        startExperiment(100, 50, List.of());
        newSession();
        var a = activity("Wspinaczka", 80);
        var b = activity("Kajaki", 80);
        Callable<Integer> tapA = () -> mvc.perform(post("/api/approvals/" + a + "/decision").with(auth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\",\"key\":\"ka\"}")).andReturn().getResponse().getStatus();
        Callable<Integer> tapB = () -> mvc.perform(post("/api/approvals/" + b + "/decision").with(auth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"approve\",\"key\":\"kb\"}")).andReturn().getResponse().getStatus();
        List<Integer> codes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            codes = pool.invokeAll(List.of(tapA, tapB)).stream().map(f -> {
                try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).toList();
        }
        // one is applied; the other either loses the plan-version race or no longer fits the budget
        assertThat(codes).contains(200);
        assertThat(budget().get("reservedGrosze").asLong()).isEqualTo(80_00);
        assertThat(budget().get("availableGrosze").asLong()).isEqualTo(20_00);
    }

    @Test
    void sevenSimulatedDaysCarryRatingsForwardAndReportMatchesLog() throws Exception {
        startExperiment(300, 50, List.of());
        int[] ratings = {3, 4, 2, 5, 4, 3, 4};
        for (int day = 1; day <= 7; day++) {
            newSession();
            if (day == 2) {
                assertThat(provider.lastSpec.instructions()).contains("Dzień 2 z 7").contains("Wczorajsza ocena dnia: 3/5")
                        .contains("komentarz: „dzień 1”");
            }
            var plan = tool("propose_daily_plan", """
                    {"items":[{"title":"Spacer","startTime":"11:00","durationMin":45,"reason":"przerwa"},
                              {"title":"Czytanie","startTime":"20:00","durationMin":30,"reason":"wyciszenie"}]}""");
            decide(plan.at("/output/approvalId").asText(), status().isOk());
            send(post("/api/tasks/" + taskId("Spacer") + "/status"), Map.of("status", "done", "actualMin", 50), status().isOk());
            if (day == 3) {
                send(post("/api/tasks/" + taskId("Czytanie") + "/status"), Map.of("status", "refused", "reason", "zmęczenie"), status().isOk());
                // plan change on day 3 too: move nothing paid, just a new version
                decide(activity("Wspinaczka", 60), status().isOk());
                tool("record_expense", "{\"amountPln\":55,\"description\":\"Ścianka\",\"taskId\":\"" + taskId("Wspinaczka") + "\"}");
            } else {
                send(post("/api/tasks/" + taskId("Czytanie") + "/status"), Map.of("status", "done"), status().isOk());
            }
            send(put("/api/experiments/current/reviews/" + today()), Map.of("rating", ratings[day - 1], "comment", "dzień " + day), status().isOk());
            // editable: one review per day
            if (day == 5) send(put("/api/experiments/current/reviews/" + today()), Map.of("rating", 4, "comment", "dzień 5"), status().isOk());
            assertThat(send(get("/api/experiments/current"), null, status().isOk()).get("dayIndex").asInt()).isEqualTo(day);
            clock.advance(Duration.ofDays(1));
        }
        var current = send(get("/api/experiments/current"), null, status().isOk());
        assertThat(current.at("/experiment/status").asText()).isEqualTo("completed");

        var report = send(get("/api/experiments/" + experimentId + "/report"), null, status().isOk());
        assertThat(report.get("days").size()).isEqualTo(7);
        assertThat(report.get("days").findValuesAsText("rating")).containsExactly("3", "4", "2", "5", "4", "3", "4");
        assertThat(report.get("refused").asInt()).isEqualTo(1);
        assertThat(report.get("refusals").get(0).get("reason").asText()).isEqualTo("zmęczenie");
        assertThat(report.get("planned").asInt()).isEqualTo(15); // 2 per day + climbing on day 3
        assertThat(report.get("done").asInt()).isEqualTo(13);
        assertThat(report.at("/budget/spentGrosze").asLong()).isEqualTo(55_00);
        assertThat(report.at("/days/2/spentGrosze").asLong()).isEqualTo(55_00);
        assertThat(report.at("/days/0/actualMinutes").asLong()).isEqualTo(50 + 30);
        assertThat(report.get("averageRating").asDouble()).isEqualTo(3.57);
        var logged = db.sql("SELECT coalesce(sum(amount_grosze), 0) FROM expenses").query(Long.class).single();
        assertThat(report.at("/budget/spentGrosze").asLong()).isEqualTo(logged);
    }

    @Test
    void apiSpendIsTrackedSeparatelyAndCapsNewSessions() throws Exception {
        startExperiment(100, 1, List.of()); // 1 zł of API ≈ 1 minute at the default rate
        newSession();
        db.sql("UPDATE sessions SET connected_at = now() - interval '3 minutes', ended_at = now(), status = 'ended'").update();
        var st = send(get("/api/experiments/current"), null, status().isOk());
        assertThat(st.at("/api/minutes").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(st.at("/budget/spentGrosze").asLong()).isZero(); // not mixed with the activity budget
        send(post("/api/sessions"), null, status().isTooManyRequests());
    }
}
