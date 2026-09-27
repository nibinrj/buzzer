package dev.nibin.buzzer.quiz.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One row of question_options, without its keys (question_id and position come from the collection).
 * A record: Hibernate 6.2+ supports records as embeddables, and value equality is exactly what an
 * element collection needs to detect unchanged options.
 */
@Embeddable
public record OptionEmbeddable(
        @Column(name = "text", nullable = false, length = 120) String text,
        @Column(name = "correct", nullable = false) boolean correct) {
}
