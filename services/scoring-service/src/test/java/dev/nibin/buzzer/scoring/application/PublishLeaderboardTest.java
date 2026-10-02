package dev.nibin.buzzer.scoring.application;

import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.scoring.domain.Leaderboard;
import dev.nibin.buzzer.scoring.domain.Leaderboard.Placement;
import dev.nibin.buzzer.scoring.domain.PlayerPoints;
import dev.nibin.buzzer.scoring.domain.PlayerTotal;
import dev.nibin.buzzer.scoring.domain.ScoringRepository;
import dev.nibin.buzzer.scoring.domain.ScoringSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishLeaderboardTest {

    private final ScoringRepository scoring = mock(ScoringRepository.class);
    private final Leaderboard leaderboard = mock(Leaderboard.class);
    private final ScoreUpdatePublisher publisher = mock(ScoreUpdatePublisher.class);
    private final PublishLeaderboard publish = new PublishLeaderboard(scoring, leaderboard, publisher);

    private final UUID session = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID cid = UUID.randomUUID();
    private final PlayerPoints bobsTotal = new PlayerPoints(bob, 900);
    private final List<PlayerPoints> top = List.of(new PlayerPoints(ada, 1000), bobsTotal, new PlayerPoints(cid, 900));

    @Test
    void aPlayerInTheTopPublishesTheRankedTopWithTheSessionsVersion() {
        when(scoring.pointsOf(session, bob)).thenReturn(Optional.of(bobsTotal));
        when(leaderboard.raise(session, bobsTotal)).thenReturn(Optional.of(new Placement(1, top)));
        when(scoring.session(session)).thenReturn(Optional.of(new ScoringSession(session, 5, 1L, null, 7)));

        assertThat(publish.afterAnswer(session, bob)).isEqualTo(PublishLeaderboard.Outcome.PUBLISHED);

        ArgumentCaptor<ScoreUpdated> sent = ArgumentCaptor.forClass(ScoreUpdated.class);
        verify(publisher).publish(sent.capture());
        assertThat(sent.getValue().sessionId()).isEqualTo(session);
        assertThat(sent.getValue().version()).isEqualTo(7);
        assertThat(sent.getValue().schemaVersion()).isEqualTo(ScoreUpdated.SCHEMA_VERSION);
        assertThat(sent.getValue().top10()).containsExactly(
                new ScoreUpdated.Entry(1, ada, 1000),
                new ScoreUpdated.Entry(2, bob, 900),
                new ScoreUpdated.Entry(2, cid, 900));
    }

    @Test
    void aPlayerOutsideTheTopPublishesNothing() {
        when(scoring.pointsOf(session, bob)).thenReturn(Optional.of(bobsTotal));
        when(leaderboard.raise(session, bobsTotal)).thenReturn(Optional.of(new Placement(Leaderboard.SIZE, top)));

        assertThat(publish.afterAnswer(session, bob)).isEqualTo(PublishLeaderboard.Outcome.OUTSIDE_TOP);

        verify(publisher, never()).publish(any());
        verify(scoring, never()).session(any()); // not even the version is read
    }

    @Test
    void aMissingLeaderboardIsRebuiltFromPostgresThenRaisedAgain() {
        when(scoring.pointsOf(session, bob)).thenReturn(Optional.of(bobsTotal));
        when(leaderboard.raise(session, bobsTotal))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Placement(1, top)));
        when(scoring.totals(session)).thenReturn(List.of(new PlayerTotal(ada, 1000, 2, 1),
                new PlayerTotal(bob, 900, 1, 1), new PlayerTotal(cid, 900, 3, 1)));
        when(scoring.session(session)).thenReturn(Optional.of(new ScoringSession(session, null, null, null, 3)));

        publish.afterAnswer(session, bob);

        InOrder order = inOrder(leaderboard, publisher);
        order.verify(leaderboard).raise(session, bobsTotal);
        order.verify(leaderboard).rebuild(session, top);
        order.verify(leaderboard).raise(session, bobsTotal);
        order.verify(publisher).publish(any());
    }

    @Test
    void stillMissingRightAfterTheRebuildIsAnErrorAndNothingIsPublished() {
        when(scoring.pointsOf(session, bob)).thenReturn(Optional.of(bobsTotal));
        when(leaderboard.raise(session, bobsTotal)).thenReturn(Optional.empty());
        when(scoring.totals(session)).thenReturn(List.of(new PlayerTotal(bob, 900, 1, 1)));

        assertThatIllegalStateException().isThrownBy(() -> publish.afterAnswer(session, bob));

        verify(publisher, never()).publish(any());
    }
}
