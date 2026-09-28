package dev.nibin.buzzer.session.domain;

/**
 * The host asked for something the session's state doesn't allow: start twice, next before the start or after
 * the last question, end twice. The message is written for the host.
 */
public class SessionStateException extends RuntimeException {

    public SessionStateException(String message) {
        super(message);
    }
}
