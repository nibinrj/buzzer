package dev.nibin.buzzer.session.infrastructure.websocket;

import dev.nibin.buzzer.session.application.SessionBroadcaster.AnswerRevealed;
import dev.nibin.buzzer.session.application.SessionBroadcaster.LeaderboardChanged;
import dev.nibin.buzzer.session.application.SessionBroadcaster.QuestionShown;
import dev.nibin.buzzer.session.application.SessionBroadcaster.StatusChanged;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands an event to THIS instance's simple broker, which delivers it to the clients connected here. Only
 * RedisRelayListener calls it: every event first goes round Redis, so each instance delivers it to its own clients.
 * <pre>
 * /topic/sessions/{id}/question  QuestionShown
 * /topic/sessions/{id}/status    StatusChanged
 * /topic/sessions/{id}/reveal    AnswerRevealed
 * /topic/sessions/{id}/leaderboard  LeaderboardChanged
 * </pre>
 */
@Component
public class LocalStompDelivery {

    private final SimpMessagingTemplate messaging;

    public LocalStompDelivery(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void questionShown(QuestionShown question) {
        messaging.convertAndSend(topic(question.sessionId().toString(), "question"), question);
    }

    public void statusChanged(StatusChanged change) {
        messaging.convertAndSend(topic(change.sessionId().toString(), "status"), change);
    }

    public void answerRevealed(AnswerRevealed revealed) {
        messaging.convertAndSend(topic(revealed.sessionId().toString(), "reveal"), revealed);
    }

    public void leaderboardChanged(LeaderboardChanged leaderboard) {
        messaging.convertAndSend(topic(leaderboard.sessionId().toString(), "leaderboard"), leaderboard);
    }

    private static String topic(String sessionId, String name) {
        return "/topic/sessions/" + sessionId + "/" + name;
    }
}
