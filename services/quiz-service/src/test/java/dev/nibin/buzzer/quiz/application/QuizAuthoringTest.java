package dev.nibin.buzzer.quiz.application;

import dev.nibin.buzzer.quiz.domain.Option;
import dev.nibin.buzzer.quiz.domain.Quiz;
import dev.nibin.buzzer.quiz.domain.QuizModifiedConcurrentlyException;
import dev.nibin.buzzer.quiz.domain.QuizNotPublishableException;
import dev.nibin.buzzer.quiz.domain.QuizRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ownership and version checks, with the repository mocked (it returns what it is given). */
class QuizAuthoringTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final List<Option> OPTIONS = List.of(new Option("A", true), new Option("B", false));

    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final QuizAuthoring authoring = new QuizAuthoring(quizzes);

    QuizAuthoringTest() {
        when(quizzes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createStoresADraftOwnedByTheCaller() {
        Quiz quiz = authoring.create(OWNER, "Capitals");

        assertThat(quiz.ownerId()).isEqualTo(OWNER);
        assertThat(quiz.status()).isEqualTo(Quiz.Status.DRAFT);
        verify(quizzes).save(quiz);
    }

    @Test
    void ownerCanReadTheirQuiz() {
        Quiz quiz = stored(OWNER, 0);

        assertThat(authoring.get(quiz.id(), OWNER)).isSameAs(quiz);
    }

    @Test
    void someoneElsesQuizIsNotFoundJustLikeAMissingOne() {
        Quiz quiz = stored(OWNER, 0);
        UUID missing = UUID.randomUUID();
        when(quizzes.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authoring.get(quiz.id(), STRANGER)).isInstanceOf(QuizNotFoundException.class);
        assertThatThrownBy(() -> authoring.get(missing, OWNER)).isInstanceOf(QuizNotFoundException.class);
        assertThatThrownBy(() -> authoring.rename(quiz.id(), STRANGER, 0, "Mine now"))
                .isInstanceOf(QuizNotFoundException.class);
        verify(quizzes, never()).save(any());
    }

    @Test
    void strangerGets404EvenWithAWrongVersion() {
        Quiz quiz = stored(OWNER, 3);

        // Ownership is checked before the version: a stranger learns nothing, not even "wrong version".
        assertThatThrownBy(() -> authoring.rename(quiz.id(), STRANGER, 0, "X"))
                .isInstanceOf(QuizNotFoundException.class);
    }

    @Test
    void changeBasedOnAnOldVersionIsRefused() {
        Quiz quiz = stored(OWNER, 3);

        assertThatThrownBy(() -> authoring.rename(quiz.id(), OWNER, 2, "New"))
                .isInstanceOf(QuizModifiedConcurrentlyException.class);
        verify(quizzes, never()).save(any());
    }

    @Test
    void renameAndQuestionChangesAreSaved() {
        Quiz quiz = stored(OWNER, 0);

        authoring.rename(quiz.id(), OWNER, 0, "Renamed");
        QuizAuthoring.AddedQuestion added = authoring.addQuestion(quiz.id(), OWNER, 0, "Q?", 20, OPTIONS);
        authoring.replaceQuestion(quiz.id(), added.questionId(), OWNER, 0, "Q, edited?", 30, OPTIONS);

        assertThat(quiz.title()).isEqualTo("Renamed");
        assertThat(added.quiz().questions()).extracting(q -> q.id()).containsExactly(added.questionId());
        assertThat(quiz.questions().getFirst().text()).isEqualTo("Q, edited?");
    }

    @Test
    void completeQuizIsPublishedAndSaved() {
        Quiz quiz = stored(OWNER, 0);
        quiz.addQuestion("Q?", 20, OPTIONS);

        Quiz published = authoring.publish(quiz.id(), OWNER, 0);

        assertThat(published.status()).isEqualTo(Quiz.Status.PUBLISHED);
        verify(quizzes).save(quiz);
    }

    @Test
    void incompleteQuizIsNotPublishedAndNothingIsSaved() {
        Quiz quiz = stored(OWNER, 0);

        assertThatThrownBy(() -> authoring.publish(quiz.id(), OWNER, 0))
                .isInstanceOf(QuizNotPublishableException.class);
        assertThat(quiz.status()).isEqualTo(Quiz.Status.DRAFT);
        verify(quizzes, never()).save(any());
    }

    @Test
    void publishIsOwnerOnlyAndVersionChecked() {
        Quiz quiz = stored(OWNER, 2);
        quiz.addQuestion("Q?", 20, OPTIONS);

        assertThatThrownBy(() -> authoring.publish(quiz.id(), STRANGER, 2)).isInstanceOf(QuizNotFoundException.class);
        assertThatThrownBy(() -> authoring.publish(quiz.id(), OWNER, 1))
                .isInstanceOf(QuizModifiedConcurrentlyException.class);
        assertThat(quiz.status()).isEqualTo(Quiz.Status.DRAFT);
    }

    @Test
    void listMineIsSortedByTitleIgnoringCase() {
        Quiz b = Quiz.create(OWNER, "banana");
        Quiz a = Quiz.create(OWNER, "Apple");
        Quiz c = Quiz.create(OWNER, "cherry");
        when(quizzes.findByOwnerId(OWNER)).thenReturn(List.of(b, c, a));

        assertThat(authoring.listMine(OWNER)).extracting(Quiz::title).containsExactly("Apple", "banana", "cherry");
    }

    private Quiz stored(UUID owner, long version) {
        Quiz quiz = new Quiz(UUID.randomUUID(), owner, "Quiz", Quiz.Status.DRAFT, List.of(), version);
        when(quizzes.findById(quiz.id())).thenReturn(Optional.of(quiz));
        return quiz;
    }
}
