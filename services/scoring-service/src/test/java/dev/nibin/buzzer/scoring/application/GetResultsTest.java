package dev.nibin.buzzer.scoring.application;

import dev.nibin.buzzer.scoring.application.GetResults.FinalResults;
import dev.nibin.buzzer.scoring.application.GetResults.LiveLeaderboard;
import dev.nibin.buzzer.scoring.domain.Leaderboard;
import dev.nibin.buzzer.scoring.domain.PlayerPoints;
import dev.nibin.buzzer.scoring.domain.PlayerTotal;
import dev.nibin.buzzer.scoring.domain.Ranking.Ranked;
import dev.nibin.buzzer.scoring.domain.ScoringRepository;
import dev.nibin.buzzer.scoring.domain.ScoringSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetResultsTest {

    private final ScoringRepository scoring = mock(ScoringRepository.class);
    private final Leaderboard leaderboard = mock(Leaderboard.class);
    private final GetResults results = new GetResults(scoring, leaderboard);

    private final UUID session = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void theLiveLeaderboardIsTheRankedRedisTopWithTheVersion() {
        when(scoring.session(session)).thenReturn(Optional.of(running(4)));
        when(leaderboard.top(session)).thenReturn(Optional.of(List.of(
                new PlayerPoints(ada, 1000), new PlayerPoints(bob, 1000))));

        LiveLeaderboard board = results.leaderboard(session);

        assertThat(board.version()).isEqualTo(4);
        assertThat(board.top()).extracting(Ranked::rank).containsExactly(1, 1);
        verify(leaderboard, never()).rebuild(any(), any());
    }

    @Test
    void aMissingRedisCopyIsRebuiltFromPostgresBeforeReading() {
        when(scoring.session(session)).thenReturn(Optional.of(running(2)));
        when(leaderboard.top(session))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(List.of(new PlayerPoints(ada, 1000))));
        when(scoring.totals(session)).thenReturn(List.of(new PlayerTotal(ada, 1000, 1, 1)));

        LiveLeaderboard board = results.leaderboard(session);

        verify(leaderboard).rebuild(session, List.of(new PlayerPoints(ada, 1000)));
        assertThat(board.top()).extracting(Ranked::item).containsExactly(new PlayerPoints(ada, 1000));
    }

    @Test
    void aSessionWithoutAnswersHasAnEmptyLeaderboardAndNothingIsRebuilt() {
        when(scoring.session(session)).thenReturn(Optional.of(running(0)));
        when(leaderboard.top(session)).thenReturn(Optional.empty());
        when(scoring.totals(session)).thenReturn(List.of());

        assertThat(results.leaderboard(session).top()).isEmpty();
        verify(leaderboard, never()).rebuild(any(), any());
    }

    @Test
    void finalResultsAreEveryPlayerRankedOnceTheSessionEnded() {
        when(scoring.session(session)).thenReturn(Optional.of(new ScoringSession(session, 3, 1_000L, 5_000L, 2)));
        when(scoring.totals(session)).thenReturn(List.of(new PlayerTotal(ada, 1000, 2, 1),
                new PlayerTotal(bob, 0, 2, 0)));

        FinalResults finals = results.finalResults(session);

        assertThat(finals.endedAt()).isEqualTo(Instant.ofEpochMilli(5_000L));
        assertThat(finals.players()).extracting(Ranked::rank).containsExactly(1, 2);
        assertThat(finals.players()).extracting(line -> line.item().playerId()).containsExactly(ada, bob);
    }

    @Test
    void finalResultsBeforeTheEndAreRefused() {
        when(scoring.session(session)).thenReturn(Optional.of(running(2)));

        assertThatThrownBy(() -> results.finalResults(session)).isInstanceOf(SessionNotEndedException.class);
        verify(scoring, never()).totals(any());
    }

    @Test
    void anUnknownSessionIsNotFoundForBoth() {
        when(scoring.session(session)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> results.leaderboard(session)).isInstanceOf(SessionNotFoundException.class);
        assertThatThrownBy(() -> results.finalResults(session)).isInstanceOf(SessionNotFoundException.class);
    }

    private ScoringSession running(long version) {
        return new ScoringSession(session, 3, 1_000L, null, version);
    }
}
