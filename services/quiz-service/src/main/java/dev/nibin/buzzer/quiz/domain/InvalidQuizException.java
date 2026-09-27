package dev.nibin.buzzer.quiz.domain;

/**
 * A structural rule of the quiz domain was broken (blank text, too long, time limit out of range, too many
 * options or questions). The message is written for the user. The API turns this into 400; a plain
 * IllegalArgumentException from anywhere else stays a 500, because that one means a bug.
 */
public class InvalidQuizException extends IllegalArgumentException {

    public InvalidQuizException(String message) {
        super(message);
    }
}
