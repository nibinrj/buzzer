package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.session.application.SessionBroadcaster.LeaderboardChanged;
import dev.nibin.buzzer.session.domain.LeaderboardVersions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushLeaderboardTest {

    private final LeaderboardVersions versions = mock(LeaderboardVersions.class);
    private final SessionBroadcaster broadcaster = mock(SessionBroadcaster.class);
    private final PushLeaderboard push = new PushLeaderboard(versions, broadcaster);

    private final UUID session = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final ScoreUpdated update = new ScoreUpdated(UUID.randomUUID(), session,
            List.of(new ScoreUpdated.Entry(1, ada, 1000), new ScoreUpdated.Entry(2, bob, 900)), 7,
            ScoreUpdated.SCHEMA_VERSION);

    @Test
    void aLeaderboardThatIsNotOlderIsBroadcastAsItCame() {
        when(versions.acceptIfNotOlder(session, 7)).thenReturn(true);

        assertThat(push.push(update)).isTrue();

        verify(broadcaster).leaderboardChanged(new LeaderboardChanged(session, 7, List.of(
                new LeaderboardChanged.Line(1, ada, 1000), new LeaderboardChanged.Line(2, bob, 900))));
    }

    @Test
    void anOlderLeaderboardIsNotBroadcast() {
        when(versions.acceptIfNotOlder(session, 7)).thenReturn(false);

        assertThat(push.push(update)).isFalse();

        verify(broadcaster, never()).leaderboardChanged(any());
    }

    @Test
    void withRedisDownNothingIsBroadcastAndNothingIsThrown() {
        when(versions.acceptIfNotOlder(session, 7)).thenThrow(new RedisConnectionFailureException("Redis is down"));

        assertThat(push.push(update)).isFalse(); // dropped, not retried: the next leaderboard replaces it

        verify(broadcaster, never()).leaderboardChanged(any());
    }
}
