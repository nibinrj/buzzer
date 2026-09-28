package dev.nibin.buzzer.session.api;

import dev.nibin.buzzer.session.application.GetSessionState.SessionState;
import dev.nibin.buzzer.session.domain.LiveState.RosterEntry;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/sessions/{id}/state. Sent to players, so it must never carry the correct answer of an open question:
 * CurrentQuestion copies the fields it shows one by one, and gives SessionQuestion.correctOption (as revealedOption)
 * only once the question is closed, i.e. after the host revealed it to everyone anyway.
 *
 * @param currentQuestion  null in the lobby, between questions and after the end
 * @param questionDeadline null whenever currentQuestion is
 */
public record SessionStateResponse(UUID sessionId, String roomCode, String quizTitle, Session.Status status,
        int questionCount, CurrentQuestion currentQuestion, Instant questionDeadline, List<PlayerView> players) {

    static SessionStateResponse from(SessionState state) {
        Session session = state.session();
        return new SessionStateResponse(session.id(), session.roomCode().value(), session.quizTitle(),
                state.live().status(), session.questions().size(),
                state.live().currentQuestionIndex()
                        .map(index -> CurrentQuestion.of(index, session.questions().get(index),
                                state.live().questionOpen()))
                        .orElse(null),
                state.live().questionDeadline().orElse(null),
                state.live().players().stream().map(PlayerView::of).toList());
    }

    /**
     * Options are addressed by index: an answer says "option 2".
     *
     * @param revealed       the host revealed the answer: the question takes no more answers. false does NOT mean
     *                       an answer would still count: after the deadline (questionDeadline) it is LATE, even
     *                       before the reveal. A client may answer only while !revealed and before the deadline.
     * @param revealedOption the correct option's index once revealed; null before
     */
    public record CurrentQuestion(int index, UUID questionId, String text, int timeLimitSeconds,
            List<String> options, boolean revealed, Integer revealedOption) {

        static CurrentQuestion of(int index, SessionQuestion question, boolean open) {
            return new CurrentQuestion(index, question.questionId(), question.text(), question.timeLimitSeconds(),
                    question.options(), !open, open ? null : question.correctOption());
        }
    }

    public record PlayerView(UUID playerId, String displayName) {

        static PlayerView of(RosterEntry entry) {
            return new PlayerView(entry.playerId(), entry.displayName());
        }
    }
}
