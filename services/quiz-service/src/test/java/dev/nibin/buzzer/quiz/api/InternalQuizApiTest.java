package dev.nibin.buzzer.quiz.api;

import com.jayway.jsonpath.JsonPath;
import dev.nibin.buzzer.quiz.ApiIntegrationTest;
import dev.nibin.buzzer.quiz.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The service-to-service snapshot, as session-service will call it: no token at all. */
@ApiIntegrationTest
class InternalQuizApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void publishedQuizSnapshotIncludesCorrectAnswersWithoutAnyToken() throws Exception {
        UUID owner = UUID.randomUUID();
        String quizId = createQuiz(owner);
        addQuestion(owner, quizId, "Capital of France?", 0);
        addQuestion(owner, quizId, "Capital of Japan?", 1);
        publish(owner, quizId, 2);

        mvc.perform(get("/internal/quizzes/" + quizId + "/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(quizId))
                .andExpect(jsonPath("$.ownerId").value(owner.toString()))
                .andExpect(jsonPath("$.questions[*].text", contains("Capital of France?", "Capital of Japan?")))
                .andExpect(jsonPath("$.questions[0].timeLimitSeconds").value(20))
                .andExpect(jsonPath("$.questions[0].options", hasSize(2)))
                .andExpect(jsonPath("$.questions[0].options[*].correct", contains(true, false)))
                // Internal contract: no authoring fields.
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void draftIsNotAvailableAsASnapshot() throws Exception {
        UUID owner = UUID.randomUUID();
        String quizId = createQuiz(owner);
        addQuestion(owner, quizId, "Q?", 0);

        mvc.perform(get("/internal/quizzes/" + quizId + "/snapshot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Quiz not found"));
    }

    @Test
    void unknownQuizIs404() throws Exception {
        mvc.perform(get("/internal/quizzes/" + UUID.randomUUID() + "/snapshot")).andExpect(status().isNotFound());
    }

    @Test
    void theUserFacingApiStillNeedsAToken() throws Exception {
        // Opening /internal/** must not have opened anything else.
        mvc.perform(get("/api/quizzes/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor host(UUID userId) {
        return jwt().jwt(builder -> builder.subject(userId.toString()).claim("roles", List.of("HOST")))
                .authorities(SecurityConfig.rolesToAuthorities());
    }

    private String createQuiz(UUID owner) throws Exception {
        String body = mvc.perform(post("/api/quizzes").with(host(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Capitals\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void addQuestion(UUID owner, String quizId, String text, long version) throws Exception {
        mvc.perform(post("/api/quizzes/" + quizId + "/questions").with(host(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"" + text + "\", \"timeLimitSeconds\": 20, \"version\": " + version
                                + ", \"options\": [{\"text\": \"Right\", \"correct\": true}, {\"text\": \"Wrong\", \"correct\": false}]}"))
                .andExpect(status().isCreated());
    }

    private void publish(UUID owner, String quizId, long version) throws Exception {
        mvc.perform(post("/api/quizzes/" + quizId + "/publish").with(host(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": " + version + "}"))
                .andExpect(status().isOk());
    }
}
