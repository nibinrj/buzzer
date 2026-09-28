package dev.nibin.buzzer.session.domain;

/** Another session already uses this room code. Expected now and then; the caller picks a new code. */
public class RoomCodeTakenException extends RuntimeException {

    public RoomCodeTakenException(RoomCode roomCode) {
        super("Room code " + roomCode.value() + " is taken");
    }
}
