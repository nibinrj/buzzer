package dev.nibin.buzzer.session.application;

/**
 * No such session for this caller: the room code or id doesn't exist, or the caller is neither its host nor
 * one of its players. Deliberately one answer (404), so outsiders can't probe which sessions exist.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException() {
        super("Session not found");
    }
}
