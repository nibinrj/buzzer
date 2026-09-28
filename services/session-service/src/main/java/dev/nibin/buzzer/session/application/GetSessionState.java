package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.domain.LiveState;
import dev.nibin.buzzer.session.domain.LiveState.RosterEntry;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Use case: everything a (re)connecting client needs to draw the screen. Only the session's host and its
 * players may ask; for anyone else the session doesn't exist.
 * <p>
 * Live state comes from Redis. If Redis has none (expired, emptied, or never written because Redis was down at
 * creation), it is rebuilt from Postgres, written back, and read again: a join that raced the rebuild is then
 * included too.
 */
@Service
public class GetSessionState {

    private final SessionRepository sessions;
    private final PlayerRepository players;
    private final LiveStateRepository liveState;

    public GetSessionState(SessionRepository sessions, PlayerRepository players, LiveStateRepository liveState) {
        this.sessions = sessions;
        this.players = players;
        this.liveState = liveState;
    }

    /**
     * @throws SessionNotFoundException      no such session, or the caller is neither its host nor a player
     * @throws LiveStateUnavailableException Redis can't be reached
     */
    public SessionState get(UUID sessionId, UUID callerId) {
        Session session = sessions.findById(sessionId)
                .filter(found -> found.hostId().equals(callerId) || players.find(sessionId, callerId).isPresent())
                .orElseThrow(SessionNotFoundException::new);
        LiveState live = liveState.find(sessionId).orElseGet(() -> rebuild(session));
        return new SessionState(session, live);
    }

    private LiveState rebuild(Session session) {
        LiveState fromRecord = LiveState.of(session.id(), session.status(),
                players.findBySession(session.id()).stream().map(RosterEntry::of).toList());
        liveState.restore(fromRecord);
        return liveState.find(session.id()).orElse(fromRecord);
    }

    /** The frozen session plus its live state. currentQuestion() still has the correct answer: the API strips it. */
    public record SessionState(Session session, LiveState live) {

        public Optional<SessionQuestion> currentQuestion() {
            return live.currentQuestionIndex().map(session.questions()::get);
        }
    }
}
