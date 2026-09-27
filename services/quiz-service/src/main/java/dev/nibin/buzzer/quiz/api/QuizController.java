package dev.nibin.buzzer.quiz.api;

import dev.nibin.buzzer.quiz.application.QuizAuthoring;
import dev.nibin.buzzer.quiz.application.QuizAuthoring.AddedQuestion;
import dev.nibin.buzzer.quiz.domain.Option;
import dev.nibin.buzzer.quiz.domain.Quiz;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * HOST-only (see SecurityConfig). The caller is always the JWT's sub. Requests only check that fields are
 * present; the limits (lengths, 5-60 s, max 6 options...) are the domain's, reported as 400 by the handler.
 */
@RestController
@RequestMapping("/api/quizzes")
public class QuizController {

    private final QuizAuthoring authoring;

    public QuizController(QuizAuthoring authoring) {
        this.authoring = authoring;
    }

    @PostMapping
    public ResponseEntity<QuizResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateQuizRequest request) {
        Quiz quiz = authoring.create(userId(jwt), request.title());
        return ResponseEntity.created(URI.create("/api/quizzes/" + quiz.id())).body(QuizResponse.from(quiz));
    }

    /** Only owner=me: a host lists their own quizzes, never someone else's. */
    @GetMapping
    public List<QuizResponse.Summary> list(@AuthenticationPrincipal Jwt jwt, @RequestParam String owner) {
        if (!"me".equals(owner)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only owner=me is supported");
        }
        return authoring.listMine(userId(jwt)).stream().map(QuizResponse.Summary::from).toList();
    }

    @GetMapping("/{quizId}")
    public QuizResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID quizId) {
        return QuizResponse.from(authoring.get(quizId, userId(jwt)));
    }

    @PutMapping("/{quizId}")
    public QuizResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID quizId,
            @Valid @RequestBody RenameQuizRequest request) {
        return QuizResponse.from(authoring.rename(quizId, userId(jwt), request.version(), request.title()));
    }

    @PostMapping("/{quizId}/questions")
    public ResponseEntity<QuizResponse> addQuestion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID quizId,
            @Valid @RequestBody QuestionRequest request) {
        AddedQuestion added = authoring.addQuestion(quizId, userId(jwt), request.version(), request.text(),
                request.timeLimitSeconds(), request.domainOptions());
        URI location = URI.create("/api/quizzes/" + quizId + "/questions/" + added.questionId());
        // The whole quiz comes back: the client needs its new version for the next change.
        return ResponseEntity.created(location).body(QuizResponse.from(added.quiz()));
    }

    /** Replaces the whole question (text, time limit, all options), keeping its id and position. */
    @PutMapping("/{quizId}/questions/{questionId}")
    public QuizResponse replaceQuestion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID quizId,
            @PathVariable UUID questionId, @Valid @RequestBody QuestionRequest request) {
        return QuizResponse.from(authoring.replaceQuestion(quizId, questionId, userId(jwt), request.version(),
                request.text(), request.timeLimitSeconds(), request.domainOptions()));
    }

    /**
     * An action, not a field update, hence POST on a sub-resource. 422 lists everything that blocks publishing;
     * 409 if it's already published or the version is stale.
     */
    @PostMapping("/{quizId}/publish")
    public QuizResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID quizId,
            @Valid @RequestBody PublishRequest request) {
        return QuizResponse.from(authoring.publish(quizId, userId(jwt), request.version()));
    }

    /** Tokens come from identity-service, whose sub is always a user or guest UUID. */
    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    // Wrapper types (Long, Integer, Boolean) + @NotNull: a missing field is a 400, not a silent 0 or false.

    public record CreateQuizRequest(@NotNull String title) {
    }

    public record RenameQuizRequest(@NotNull String title, @NotNull Long version) {
    }

    public record PublishRequest(@NotNull Long version) {
    }

    public record QuestionRequest(
            @NotNull String text,
            @NotNull Integer timeLimitSeconds,
            @NotNull List<@NotNull @Valid OptionRequest> options,
            @NotNull Long version) {

        List<Option> domainOptions() {
            return options.stream().map(option -> new Option(option.text(), option.correct())).toList();
        }
    }

    public record OptionRequest(@NotNull String text, @NotNull Boolean correct) {
    }
}
