package dev.nibin.buzzer.scoring.api;

import dev.nibin.buzzer.scoring.application.SessionNotEndedException;
import dev.nibin.buzzer.scoring.application.SessionNotFoundException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one error handler for this service: application exceptions → RFC 9457 ProblemDetail.
 * (401/403 are produced earlier, by Spring Security's filters, before any controller runs. A malformed session id
 * is a 400 from ResponseEntityExceptionHandler.)
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    ProblemDetail handleSessionNotFound(SessionNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Session not found", "There are no results for this session.");
    }

    @ExceptionHandler(SessionNotEndedException.class)
    ProblemDetail handleSessionNotEnded(SessionNotEndedException e) {
        return problem(HttpStatus.CONFLICT, "Session not ended",
                "Final results exist once the host has ended the game. The live leaderboard is available now.");
    }

    /**
     * Postgres or Redis unreachable (both Spring exceptions extend this one): our dependency, not the client's fault.
     * Safe to retry: both endpoints only read. Nothing about the cause reaches the client.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ProblemDetail handleStorageUnavailable(DataAccessResourceFailureException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Results unavailable",
                "Results can't be read right now. Try again in a few seconds.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
