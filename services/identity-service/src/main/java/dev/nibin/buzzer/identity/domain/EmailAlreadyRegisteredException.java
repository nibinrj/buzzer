package dev.nibin.buzzer.identity.domain;

/** Thrown when registering an email that already belongs to a user. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        // No email in the message: exception messages end up in logs.
        super("Email already registered");
    }
}
