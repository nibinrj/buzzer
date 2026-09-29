package dev.nibin.buzzer.scoring.api;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.scoring.ScoringIntegrationTest;
import dev.nibin.buzzer.scoring.application.ApplyScoringEvent;
import dev.nibin.buzzer.scoring.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The results endpoints over the real filter chain, Postgres and Redis. Scores are put in through ApplyScoringEvent
 * directly (no Kafka), so the Redis leaderboard doesn't exist yet: every leaderboard read here also exercises the
 * rebuild from Postgres.
 */
@ScoringIntegrationTest
class ResultsApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplyScoringEvent apply;

    @Autowired
    private StringRedisTemplate redis;

    private final UUID session = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID cid = UUID.randomUUID();

    @Test
    void theLeaderboardRanksTiesTogetherCarriesTheVersionAndIsRebuiltIntoRedis() throws Exception {
        answer(ada, true, 1); // 1000
        answer(bob, true, 1); // 1000, first correct on another question
        answer(cid, true, 2); // 900

        read(leaderboard(), player())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(session.toString()))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.top[*].rank", contains(1, 1, 3)))
                .andExpect(jsonPath("$.top[*].points", contains(1000, 1000, 900)))
                .andExpect(jsonPath("$.top[2].playerId").value(cid.toString()));
        assertThat(redis.hasKey("leaderboard:{" + session + "}")).isTrue();
    }

    @Test
    void aHostMayReadItToo() throws Exception {
        answer(ada, true, 1);

        read(leaderboard(), host()).andExpect(status().isOk());
    }

    @Test
    void aStartedSessionWithoutAnswersHasAnEmptyLeaderboard() throws Exception {
        apply.sessionStarted(new SessionStarted(UUID.randomUUID(), session, UUID.randomUUID(), UUID.randomUUID(), 5,
                1_000L, SessionStarted.SCHEMA_VERSION));

        read(leaderboard(), player())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.top", empty()));
    }

    @Test
    void finalResultsAreAConflictUntilTheSessionEndsThenListEveryPlayer() throws Exception {
        answer(ada, true, 1);
        answer(bob, false, 0);

        read(finalResults(), player())
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Session not ended"));

        apply.sessionEnded(new SessionEnded(UUID.randomUUID(), session, 1_700_000_000_000L,
                SessionEnded.SCHEMA_VERSION));

        read(finalResults(), player())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").value("2023-11-14T22:13:20Z"))
                .andExpect(jsonPath("$.players[*].playerId", contains(ada.toString(), bob.toString())))
                .andExpect(jsonPath("$.players[*].rank", contains(1, 2)))
                .andExpect(jsonPath("$.players[*].points", contains(1000, 0)))
                .andExpect(jsonPath("$.players[*].answers", contains(1, 1)))
                .andExpect(jsonPath("$.players[*].correctAnswers", contains(1, 0)));
    }

    @Test
    void anAnswerReadAfterTheEndStillCountsInTheFinalResults() throws Exception {
        answer(ada, true, 2);
        apply.sessionEnded(new SessionEnded(UUID.randomUUID(), session, 2_000L, SessionEnded.SCHEMA_VERSION));
        answer(bob, true, 1); // e.g. back from a retry topic after SessionEnded was read

        read(finalResults(), player())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[*].playerId", contains(bob.toString(), ada.toString())));
    }

    @Test
    void anUnknownSessionIsNotFoundForBoth() throws Exception {
        read(leaderboard(), player())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Session not found"));
        read(finalResults(), player()).andExpect(status().isNotFound());
    }

    @Test
    void aMalformedSessionIdIsABadRequest() throws Exception {
        read("/api/results/sessions/not-a-uuid/leaderboard", player()).andExpect(status().isBadRequest());
    }

    @Test
    void withoutATokenIsUnauthorized() throws Exception {
        mvc.perform(get(leaderboard()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutAHostOrPlayerRoleIsForbidden() throws Exception {
        RequestPostProcessor noRole = jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())
                .claim("roles", List.of())).authorities(SecurityConfig.rolesToAuthorities());

        read(leaderboard(), noRole).andExpect(status().isForbidden());
    }

    @Test
    void everythingElseIsDenied() throws Exception {
        read("/api/results/sessions/" + session + "/answers", player()).andExpect(status().isForbidden());
    }

    private void answer(UUID player, boolean correct, int correctRank) {
        apply.answerSubmitted(new AnswerSubmitted(UUID.randomUUID(), session, UUID.randomUUID(), player, 0, correct,
                correctRank, 1, 1_000L, AnswerSubmitted.SCHEMA_VERSION));
    }

    private String leaderboard() {
        return "/api/results/sessions/" + session + "/leaderboard";
    }

    private String finalResults() {
        return "/api/results/sessions/" + session + "/final";
    }

    private ResultActions read(String path, RequestPostProcessor token) throws Exception {
        return mvc.perform(get(path).with(token));
    }

    private static RequestPostProcessor player() {
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()).claim("roles", List.of("PLAYER")))
                .authorities(SecurityConfig.rolesToAuthorities());
    }

    private static RequestPostProcessor host() {
        return jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()).claim("roles", List.of("HOST")))
                .authorities(SecurityConfig.rolesToAuthorities());
    }
}
