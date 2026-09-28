package dev.nibin.buzzer.session.application;

/** Redis couldn't be read or written. The API answers 503; every operation that throws this is safe to retry. */
public class LiveStateUnavailableException extends RuntimeException {

    public LiveStateUnavailableException(Throwable cause) {
        super("Live session state (Redis) unavailable", cause);
    }
}
