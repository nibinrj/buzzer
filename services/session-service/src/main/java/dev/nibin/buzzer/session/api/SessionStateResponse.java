package dev.nibin.buzzer.session.api;

import dev.nibin.buzzer.session.application.GetSessionState.SessionState;
import dev.nibin.buzzer.session.domain.LiveState.RosterEntry;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/sessions/{id}/state. Sent to players, so it must never carry the correct answer: CurrentQuestion
 * copies the fields it shows one by one, and SessionQuestion.correctOption is simply not one of them.
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
                        .map(index -> CurrentQuestion.of(index, session.questions().get(index)))
                        .orElse(null),
                state.live().questionDeadline().orElse(null),
                state.live().players().stream().map(PlayerView::of).toList());
    }

    /** Options are addressed by index: an answer later says "option 2". */
    public record CurrentQuestion(int index, UUID questionId, String text, int timeLimitSeconds,
            List<String> options) {

        static CurrentQuestion of(int index, SessionQuestion question) {
            return new CurrentQuestion(index, question.questionId(), question.text(), question.timeLimitSeconds(),
                    question.options());
        }
    }

    public record PlayerView(UUID playerId, String displayName) {

        static PlayerView of(RosterEntry entry) {
            return new PlayerView(entry.playerId(), entry.displayName());
        }
    }
}
