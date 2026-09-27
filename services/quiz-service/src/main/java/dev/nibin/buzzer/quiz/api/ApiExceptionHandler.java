package dev.nibin.buzzer.quiz.api;

import dev.nibin.buzzer.quiz.application.QuizNotFoundException;
import dev.nibin.buzzer.quiz.domain.InvalidQuizException;
import dev.nibin.buzzer.quiz.domain.QuestionNotFoundException;
import dev.nibin.buzzer.quiz.domain.QuizModifiedConcurrentlyException;
import dev.nibin.buzzer.quiz.domain.QuizNotEditableException;
import dev.nibin.buzzer.quiz.domain.QuizNotPublishableException;
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
 * The one error handler for this service: domain and application exceptions → RFC 9457 ProblemDetail.
 * (401/403 are produced earlier, by Spring Security's filters, before any controller runs.)
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(QuizNotFoundException.class)
    ProblemDetail handleQuizNotFound(QuizNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Quiz not found", "No quiz with this id belongs to you.");
    }

    @ExceptionHandler(QuestionNotFoundException.class)
    ProblemDetail handleQuestionNotFound(QuestionNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Question not found", "The quiz has no question with this id.");
    }

    @ExceptionHandler(QuizNotEditableException.class)
    ProblemDetail handleNotEditable(QuizNotEditableException e) {
        return problem(HttpStatus.CONFLICT, "Quiz is published", "A published quiz can no longer be changed.");
    }

    @ExceptionHandler(QuizModifiedConcurrentlyException.class)
    ProblemDetail handleModifiedConcurrently(QuizModifiedConcurrentlyException e) {
        return problem(HttpStatus.CONFLICT, "Quiz was modified",
                "The quiz was saved by someone else since you loaded it. Reload it and try again.");
    }

    /**
     * 422: the request is well-formed, but the quiz isn't complete enough to publish. All problems at once,
     * e.g. ["Question 2: needs exactly one correct option (has 0)"], so the host can fix everything in one go.
     */
    @ExceptionHandler(QuizNotPublishableException.class)
    ProblemDetail handleNotPublishable(QuizNotPublishableException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Quiz is not ready to publish",
                "Fix the listed problems, then publish again.");
        problem.setProperty("problems", e.problems());
        return problem;
    }

    /** Domain limits (lengths, time limit, option/question counts). The messages are written for users. */
    @ExceptionHandler(InvalidQuizException.class)
    ProblemDetail handleInvalidQuiz(InvalidQuizException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid quiz", e.getMessage());
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
