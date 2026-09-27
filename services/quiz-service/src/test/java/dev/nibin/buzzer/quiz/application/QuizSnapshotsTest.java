package dev.nibin.buzzer.quiz.application;

import dev.nibin.buzzer.quiz.domain.Option;
import dev.nibin.buzzer.quiz.domain.Quiz;
import dev.nibin.buzzer.quiz.domain.QuizRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuizSnapshotsTest {

    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final QuizSnapshots snapshots = new QuizSnapshots(quizzes);

    @Test
    void publishedQuizIsReturnedWhoeverOwnsIt() {
        Quiz quiz = Quiz.create(UUID.randomUUID(), "Quiz");
        quiz.addQuestion("Q?", 20, List.of(new Option("A", true), new Option("B", false)));
        quiz.publish();
        when(quizzes.findById(quiz.id())).thenReturn(Optional.of(quiz));

        assertThat(snapshots.publishedQuiz(quiz.id())).isSameAs(quiz);
    }

    @Test
    void draftIsNotFoundJustLikeAMissingQuiz() {
        Quiz draft = Quiz.create(UUID.randomUUID(), "Draft");
        UUID missing = UUID.randomUUID();
        when(quizzes.findById(draft.id())).thenReturn(Optional.of(draft));
        when(quizzes.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> snapshots.publishedQuiz(draft.id())).isInstanceOf(QuizNotFoundException.class);
        assertThatThrownBy(() -> snapshots.publishedQuiz(missing)).isInstanceOf(QuizNotFoundException.class);
    }
}
