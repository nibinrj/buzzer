package dev.nibin.buzzer.quiz.api;

import com.jayway.jsonpath.JsonPath;
import dev.nibin.buzzer.quiz.ApiIntegrationTest;
import dev.nibin.buzzer.quiz.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The quiz API end to end: security filter chain, controller, use cases, real PostgreSQL.
 * Not transactional (MockMvc runs the request in the test thread, but the use cases commit their own
 * transactions), so every test uses fresh random owners.
 */
@ApiIntegrationTest
class QuizApiTest {

    private static final String TWO_OPTIONS = """
            [{"text": "Paris", "correct": true}, {"text": "Lyon", "correct": false}]""";

    @Autowired
    private MockMvc mvc;

    // --- authentication and authorization ---

    @Test
    void noTokenIs401WithABearerChallenge() throws Exception {
        mvc.perform(get("/api/quizzes/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
    }

    @Test
    void aPlayerTokenIs403() throws Exception {
        mvc.perform(post("/api/quizzes").with(player()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Sneaky"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void endpointsNotOpenedOnPurposeAreDenied() throws Exception {
        mvc.perform(get("/api/somethingelse").with(host(UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    // --- X-User-* headers are the gateway's hint for logs, never proof of identity ---

    @Test
    void identityHeadersWithoutATokenAre401() throws Exception {
        UUID owner = UUID.randomUUID();
        String quizId = createQuiz(owner, "Mine");

        // A real owner's id and the right role: exactly what the gateway would set. Without a token it counts for nothing.
        mvc.perform(get("/api/quizzes/" + quizId)
                        .header("X-User-Id", owner.toString())
                        .header("X-User-Roles", "HOST"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
        mvc.perform(post("/api/quizzes")
                        .header("X-User-Id", owner.toString())
                        .header("X-User-Roles", "HOST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Sneaky"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anIdentityHeaderCannotOverrideTheTokensSubject() throws Exception {
        UUID alice = UUID.randomUUID();
        String alicesQuiz = createQuiz(alice, "Alice's");

        // Bob's valid token, Alice's id in the header: Bob is still Bob, and Alice's quiz doesn't exist for him.
        mvc.perform(get("/api/quizzes/" + alicesQuiz).with(host(UUID.randomUUID()))
                        .header("X-User-Id", alice.toString()))
                .andExpect(status().isNotFound());
    }

    // --- create, read, list ---

    @Test
    void createReturns201WithLocationAndTheOwnersView() throws Exception {
        UUID me = UUID.randomUUID();

        mvc.perform(post("/api/quizzes").with(host(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "  Capitals "}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/quizzes/")))
                .andExpect(jsonPath("$.title").value("Capitals"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.questions", hasSize(0)));
    }

    @Test
    void ownerSeesCorrectAnswersAndTheNewVersionAfterEachChange() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Capitals");

        MvcResult added = mvc.perform(post("/api/quizzes/" + quizId + "/questions").with(host(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(question("Capital of France?", 20, TWO_OPTIONS, 0)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/quizzes/" + quizId + "/questions/")))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String questionId = JsonPath.read(added.getResponse().getContentAsString(), "$.questions[0].id");

        mvc.perform(put("/api/quizzes/" + quizId + "/questions/" + questionId).with(host(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(question("Capital of Japan?", 30, """
                                [{"text": "Osaka", "correct": false}, {"text": "Tokyo", "correct": true}]""", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/quizzes/" + quizId).with(host(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].id").value(questionId))
                .andExpect(jsonPath("$.questions[0].text").value("Capital of Japan?"))
                .andExpect(jsonPath("$.questions[0].timeLimitSeconds").value(30))
                .andExpect(jsonPath("$.questions[0].options[*].correct", contains(false, true)));
    }

    @Test
    void listReturnsOnlyMyQuizzesSortedByTitle() throws Exception {
        UUID me = UUID.randomUUID();
        createQuiz(me, "banana");
        createQuiz(me, "Apple");
        createQuiz(UUID.randomUUID(), "Not mine");

        mvc.perform(get("/api/quizzes").param("owner", "me").with(host(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", contains("Apple", "banana")))
                .andExpect(jsonPath("$[0].questionCount").value(0))
                .andExpect(jsonPath("$[0].questions").doesNotExist());
    }

    @Test
    void listingSomeoneElsesQuizzesIsNotSupported() throws Exception {
        mvc.perform(get("/api/quizzes").param("owner", UUID.randomUUID().toString()).with(host(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    // --- ownership: someone else's quiz doesn't exist for you ---

    @Test
    void anotherHostGetsTheSame404AsForAQuizThatDoesNotExist() throws Exception {
        String quizId = createQuiz(UUID.randomUUID(), "Private");
        RequestPostProcessor stranger = host(UUID.randomUUID());

        String strangerBody = mvc.perform(get("/api/quizzes/" + quizId).with(stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Quiz not found"))
                .andReturn().getResponse().getContentAsString();
        String missingBody = mvc.perform(get("/api/quizzes/" + UUID.randomUUID()).with(stranger))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Identical apart from the request path in "instance".
        assertThat(strangerBody.replaceAll("\"instance\":\"[^\"]*\"", ""))
                .isEqualTo(missingBody.replaceAll("\"instance\":\"[^\"]*\"", ""));
        mvc.perform(put("/api/quizzes/" + quizId).with(stranger).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Mine now", "version": 0}"""))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/quizzes/" + quizId + "/questions").with(stranger).contentType(MediaType.APPLICATION_JSON)
                        .content(question("Q?", 20, TWO_OPTIONS, 0)))
                .andExpect(status().isNotFound());
    }

    // --- conflicts ---

    @Test
    void aChangeBasedOnAStaleVersionIs409() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Quiz");
        rename(me, quizId, "Tab B", 0).andExpect(status().isOk());

        rename(me, quizId, "Tab A", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Quiz was modified"));
    }

    // --- publish ---

    @Test
    void completeQuizIsPublished() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Quiz");
        addQuestion(me, quizId, TWO_OPTIONS, 0);

        publish(me, quizId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void incompleteQuizIs422WithEveryProblem() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Quiz");
        addQuestion(me, quizId, TWO_OPTIONS, 0);
        addQuestion(me, quizId, """
                [{"text": "A", "correct": false}]""", 1);

        publish(me, quizId, 2)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Quiz is not ready to publish"))
                .andExpect(jsonPath("$.problems", contains(
                        "Question 2: needs at least 2 options (has 1)",
                        "Question 2: needs exactly one correct option (has 0)")));
        mvc.perform(get("/api/quizzes/" + quizId).with(host(me))).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void emptyQuizIs422() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Quiz");

        publish(me, quizId, 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.problems", contains("A quiz needs at least one question")));
    }

    @Test
    void aPublishedQuizCannotBeChangedOrPublishedAgain() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Quiz");
        String questionId = addQuestion(me, quizId, TWO_OPTIONS, 0);
        publish(me, quizId, 1).andExpect(status().isOk());

        rename(me, quizId, "Too late", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Quiz is published"));
        mvc.perform(put("/api/quizzes/" + quizId + "/questions/" + questionId).with(host(me))
                        .contentType(MediaType.APPLICATION_JSON).content(question("Edited?", 20, TWO_OPTIONS, 2)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/quizzes/" + quizId + "/questions").with(host(me))
                        .contentType(MediaType.APPLICATION_JSON).content(question("Extra?", 20, TWO_OPTIONS, 2)))
                .andExpect(status().isConflict());
        publish(me, quizId, 2).andExpect(status().isConflict());
    }

    @Test
    void onlyTheOwnerCanPublish() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Quiz");
        addQuestion(me, quizId, TWO_OPTIONS, 0);

        publish(UUID.randomUUID(), quizId, 1).andExpect(status().isNotFound());
        mvc.perform(post("/api/quizzes/" + quizId + "/publish").with(player()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1}"))
                .andExpect(status().isForbidden());
    }

    // --- validation ---

    @Test
    void missingFieldsAre400WithFieldErrors() throws Exception {
        String quizId = createQuiz(UUID.randomUUID(), "Quiz");

        mvc.perform(post("/api/quizzes/" + quizId + "/questions").with(host(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Q?"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(
                        containsInAnyOrder("timeLimitSeconds", "options", "version")));
    }

    @Test
    void domainLimitsAre400WithTheDomainsMessage() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Quiz");

        mvc.perform(post("/api/quizzes/" + quizId + "/questions").with(host(me)).contentType(MediaType.APPLICATION_JSON)
                        .content(question("Q?", 61, TWO_OPTIONS, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid quiz"))
                .andExpect(jsonPath("$.detail").value("timeLimitSeconds must be between 5 and 60"));
        mvc.perform(post("/api/quizzes").with(host(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "   "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("title must not be blank"));
    }

    @Test
    void unknownQuestionIs404AndMalformedIdIs400() throws Exception {
        UUID me = UUID.randomUUID();
        String quizId = createQuiz(me, "Quiz");

        mvc.perform(put("/api/quizzes/" + quizId + "/questions/" + UUID.randomUUID()).with(host(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(question("Q?", 20, TWO_OPTIONS, 0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Question not found"));
        mvc.perform(get("/api/quizzes/not-a-uuid").with(host(me))).andExpect(status().isBadRequest());
    }

    // --- helpers ---

    /** A HOST token for this user, authenticated through the app's own roles → authorities mapping. */
    private static RequestPostProcessor host(UUID userId) {
        return token(userId, "HOST");
    }

    private static RequestPostProcessor player() {
        return token(UUID.randomUUID(), "PLAYER");
    }

    private static RequestPostProcessor token(UUID userId, String role) {
        return jwt().jwt(builder -> builder.subject(userId.toString()).claim("roles", List.of(role)))
                .authorities(SecurityConfig.rolesToAuthorities());
    }

    private String createQuiz(UUID owner, String title) throws Exception {
        MvcResult result = mvc.perform(post("/api/quizzes").with(host(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private ResultActions rename(UUID owner, String quizId, String title,
            long version) throws Exception {
        return mvc.perform(put("/api/quizzes/" + quizId).with(host(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"" + title + "\", \"version\": " + version + "}"));
    }

    /** Adds a question and returns its id. */
    private String addQuestion(UUID owner, String quizId, String options, long version) throws Exception {
        MvcResult result = mvc.perform(post("/api/quizzes/" + quizId + "/questions").with(host(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(question("Q?", 20, options, version)))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private ResultActions publish(UUID owner, String quizId, long version) throws Exception {
        return mvc.perform(post("/api/quizzes/" + quizId + "/publish").with(host(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\": " + version + "}"));
    }

    private static String question(String text, int timeLimitSeconds, String options, long version) {
        return "{\"text\": \"" + text + "\", \"timeLimitSeconds\": " + timeLimitSeconds
                + ", \"options\": " + options + ", \"version\": " + version + "}";
    }
}
