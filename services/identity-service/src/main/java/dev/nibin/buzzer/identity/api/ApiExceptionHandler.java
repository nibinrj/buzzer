package dev.nibin.buzzer.identity.api;

import dev.nibin.buzzer.identity.application.LoginUser;
import dev.nibin.buzzer.identity.application.RefreshSession;
import dev.nibin.buzzer.identity.domain.EmailAlreadyRegisteredException;
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
 * The one error handler for this service. Extending ResponseEntityExceptionHandler turns Spring MVC's
 * own exceptions (malformed JSON, validation, 405, ...) into RFC 9457 ProblemDetail responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail handleEmailAlreadyRegistered(EmailAlreadyRegisteredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "An account with this email already exists.");
        problem.setTitle("Email already registered");
        return problem;
    }

    /** Same response for unknown email and wrong password, so it can't be used to probe for accounts. */
    @ExceptionHandler(LoginUser.InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(LoginUser.InvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Email or password is incorrect.");
        problem.setTitle("Invalid credentials");
        return problem;
    }

    /** One response for unknown, expired, revoked and reused tokens: a thief learns nothing about which. */
    @ExceptionHandler(RefreshSession.InvalidRefreshTokenException.class)
    ProblemDetail handleInvalidRefreshToken(RefreshSession.InvalidRefreshTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "The refresh token is invalid, expired or revoked. Log in again.");
        problem.setTitle("Invalid refresh token");
        return problem;
    }

    /** Adds which fields failed and why. Rejected values are left out: one of them may be a password. */
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

    record FieldViolation(String field, String message) {
    }
}
