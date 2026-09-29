package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.application.SessionBroadcaster.AnswerRevealed;
import dev.nibin.buzzer.session.application.SessionBroadcaster.LeaderboardChanged;
import dev.nibin.buzzer.session.application.SessionBroadcaster.QuestionShown;
import dev.nibin.buzzer.session.application.SessionBroadcaster.StatusChanged;

/**
 * What travels on the relay channel between session-service instances, as JSON: exactly one of the four events,
 * the other fields null. For example {@code {"questionShown":{...},"statusChanged":null,...}}.
 */
record RelayMessage(QuestionShown questionShown, StatusChanged statusChanged, AnswerRevealed answerRevealed,
        LeaderboardChanged leaderboardChanged) {

    static RelayMessage of(QuestionShown question) {
        return new RelayMessage(question, null, null, null);
    }

    static RelayMessage of(StatusChanged change) {
        return new RelayMessage(null, change, null, null);
    }

    static RelayMessage of(AnswerRevealed revealed) {
        return new RelayMessage(null, null, revealed, null);
    }

    static RelayMessage of(LeaderboardChanged leaderboard) {
        return new RelayMessage(null, null, null, leaderboard);
    }
}
