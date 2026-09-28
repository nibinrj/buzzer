package dev.nibin.buzzer.session.api;

import dev.nibin.buzzer.session.application.LiveStateUnavailableException;
import dev.nibin.buzzer.session.application.QuizNotFoundException;
import dev.nibin.buzzer.session.application.QuizServiceUnavailableException;
import dev.nibin.buzzer.session.application.SessionNotFoundException;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionFullException;
import dev.nibin.buzzer.session.domain.SessionNotJoinableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Objects;

/**
 * The one error handler for this service: application exceptions → RFC 9457 ProblemDetail.
 * (401/403 are produced earlier, by Spring Security's filters, before any controller runs.)
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(QuizNotFoundException.class)
    ProblemDetail handleQuizNotFound(QuizNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Quiz not found", "No published quiz with this id belongs to you.");
    }

    /** Our dependency is down, not the client's fault: 503, and nothing about the cause leaks to the client. */
    @ExceptionHandler(QuizServiceUnavailableException.class)
    ProblemDetail handleQuizServiceUnavailable(QuizServiceUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Quizzes unavailable",
                "Quizzes can't be loaded right now. Try again in a few seconds.");
    }

    @ExceptionHandler(SessionNotFoundException.class)
    ProblemDetail handleSessionNotFound(SessionNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Session not found", "No session with this room code or id for you.");
    }

    @ExceptionHandler(SessionNotJoinableException.class)
    ProblemDetail handleNotJoinable(SessionNotJoinableException e) {
        return problem(HttpStatus.CONFLICT, "Session already started",
                "This game has started; new players can't join.");
    }

    @ExceptionHandler(SessionFullException.class)
    ProblemDetail handleFull(SessionFullException e) {
        return problem(HttpStatus.CONFLICT, "Session is full",
                "This game already has " + Session.MAX_PLAYERS + " players.");
    }

    /** Safe to retry: every operation that can fail this way is idempotent. */
    @ExceptionHandler(LiveStateUnavailableException.class)
    ProblemDetail handleLiveStateUnavailable(LiveStateUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Game state unavailable",
                "The game state can't be reached right now. Try again in a few seconds.");
    }

    /** Adds which fields failed and why. Rejected values are left out. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = e.getBody();
        List<FieldViolation> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    record FieldViolation(String field, String message) {
    }
}
