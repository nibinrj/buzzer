package dev.nibin.buzzer.session.infrastructure.websocket;

import dev.nibin.buzzer.session.application.SessionBroadcaster;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Broadcasts through this instance's simple broker: reaches only clients connected to THIS instance. Correct while
 * session-service runs as one task; several tasks need a Redis pub/sub relay in front of this (a later batch).
 * <pre>
 * /topic/sessions/{id}/question  QuestionShown
 * /topic/sessions/{id}/status    StatusChanged
 * </pre>
 */
@Component
class StompSessionBroadcaster implements SessionBroadcaster {

    private final SimpMessagingTemplate messaging;

    StompSessionBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @Override
    public void questionShown(QuestionShown question) {
        messaging.convertAndSend(topic(question.sessionId().toString(), "question"), question);
    }

    @Override
    public void statusChanged(StatusChanged change) {
        messaging.convertAndSend(topic(change.sessionId().toString(), "status"), change);
    }

    private static String topic(String sessionId, String name) {
        return "/topic/sessions/" + sessionId + "/" + name;
    }
}
