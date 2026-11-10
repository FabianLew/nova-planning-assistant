package pl.futureirl.nova;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AuthIT {
    @Autowired MockMvc mvc;

    @Test
    void apiRequiresLogin() throws Exception {
        mvc.perform(get("/api/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"fabian\",\"password\":\"nope-nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("bad_credentials"));
    }

    @Test
    void loginGivesAccess() throws Exception {
        var session = TestSupport.login(mvc);
        mvc.perform(get("/api/auth/me").with(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("fabian"));
    }

    @Test
    void stateChangesNeedCsrfToken() throws Exception {
        var session = TestSupport.login(mvc);
        mvc.perform(post("/api/sessions").with(session)).andExpect(status().isForbidden());
    }

    @Test
    void foreignOriginIsRejected() throws Exception {
        var session = TestSupport.login(mvc);
        mvc.perform(post("/api/sessions").with(session).with(csrf()).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("bad_origin"));
    }

    @Test
    void securityHeadersAllowMicrophoneOnlyForSelf() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("root")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Permissions-Policy", "microphone=(self), camera=()"));
    }

    @Test
    void spaRoutesResolveToTheirPage() throws Exception {
        mvc.perform(get("/rozmowa")).andExpect(content().string(org.hamcrest.Matchers.containsString("rozmowa")));
        mvc.perform(get("/rozmowa/")).andExpect(content().string(org.hamcrest.Matchers.containsString("rozmowa")));
        mvc.perform(get("/unknown/route")).andExpect(content().string(org.hamcrest.Matchers.containsString("root")));
        mvc.perform(get("/missing.js")).andExpect(status().isNotFound());
    }
}
