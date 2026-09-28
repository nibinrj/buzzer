package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.application.SessionBroadcaster.QuestionShown;
import dev.nibin.buzzer.session.application.SessionBroadcaster.StatusChanged;

/**
 * What travels on the relay channel between session-service instances, as JSON: exactly one of the two events,
 * the other field null. For example {@code {"questionShown":{...},"statusChanged":null}}.
 */
record RelayMessage(QuestionShown questionShown, StatusChanged statusChanged) {

    static RelayMessage of(QuestionShown question) {
        return new RelayMessage(question, null);
    }

    static RelayMessage of(StatusChanged change) {
        return new RelayMessage(null, change);
    }
}
