package dev.nibin.buzzer.session.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * session.quiz-client.*: where quiz-service is, and how long to wait for it.
 * <p>
 * The timeouts are the ONLY time limit on the call (the circuit breaker's TimeLimiter is switched off in
 * application.yml), so a hung quiz-service costs a host at most connect + read timeout, never a stuck thread.
 */
@Validated
@ConfigurationProperties("session.quiz-client")
public record QuizClientProperties(@NotBlank String baseUrl, @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
