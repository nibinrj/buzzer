package dev.nibin.buzzer.quiz.infrastructure.persistence;

import dev.nibin.buzzer.quiz.domain.Option;
import dev.nibin.buzzer.quiz.domain.Question;
import dev.nibin.buzzer.quiz.domain.Quiz;
import dev.nibin.buzzer.quiz.domain.Quiz.Status;
import dev.nibin.buzzer.quiz.domain.QuizModifiedConcurrentlyException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Quiz aggregate through the port, against a real PostgreSQL 16 (Flyway V1 + schema validation). */
@PersistenceTest
class JpaQuizRepositoryAdapterTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Autowired
    private JpaQuizRepositoryAdapter adapter;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Statistics statistics;

    @BeforeEach
    void resetStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void newQuizRoundTripsWithQuestionsAndOptionsInOrder() {
        Quiz quiz = Quiz.create(OWNER, "Capitals");
        Question france = quiz.addQuestion("Capital of France?", 20,
                List.of(new Option("Lyon", false), new Option("Paris", true), new Option("Nice", false)));
        Question japan = quiz.addQuestion("Capital of Japan?", 30,
                List.of(new Option("Tokyo", true), new Option("Osaka", false)));

        Quiz saved = adapter.save(quiz);
        entityManager.clear();

        assertThat(saved.version()).isZero();
        Quiz loaded = adapter.findById(quiz.id()).orElseThrow();
        assertThat(loaded.ownerId()).isEqualTo(OWNER);
        assertThat(loaded.title()).isEqualTo("Capitals");
        assertThat(loaded.status()).isEqualTo(Status.DRAFT);
        assertThat(loaded.questions()).extracting(Question::id).containsExactly(france.id(), japan.id());
        assertThat(loaded.questions().get(0).options()).containsExactly(
                new Option("Lyon", false), new Option("Paris", true), new Option("Nice", false));
        assertThat(loaded.questions().get(1).timeLimitSeconds()).isEqualTo(30);
    }

    @Test
    void loadingAQuizTakesThreeQueriesWhateverTheNumberOfQuestions() {
        Quiz quiz = Quiz.create(OWNER, "Many questions");
        for (int i = 0; i < 10; i++) {
            quiz.addQuestion("Q" + i, 20, List.of(new Option("A", true), new Option("B", false)));
        }
        adapter.save(quiz);
        entityManager.clear();
        statistics.clear();

        Quiz loaded = adapter.findById(quiz.id()).orElseThrow();

        assertThat(loaded.questions()).hasSize(10);
        // quiz + all questions + all options (batched), not 1 + 1 + 10.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void savingIncrementsTheVersionEvenWhenOnlyAQuestionChanged() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        Question question = quiz.addQuestion("Before?", 20, List.of(new Option("A", true), new Option("B", false)));
        adapter.save(quiz);
        entityManager.clear();

        Quiz loaded = adapter.findById(quiz.id()).orElseThrow();
        loaded.replaceQuestion(question.id(), "After?", 45, List.of(new Option("C", true), new Option("D", false)));
        Quiz saved = adapter.save(loaded);
        entityManager.clear();

        assertThat(saved.version()).isEqualTo(1);
        Question stored = adapter.findById(quiz.id()).orElseThrow().questions().getFirst();
        assertThat(stored.text()).isEqualTo("After?");
        assertThat(stored.timeLimitSeconds()).isEqualTo(45);
        assertThat(stored.options()).containsExactly(new Option("C", true), new Option("D", false));
    }

    @Test
    void savingAnUnchangedQuizWritesOnlyTheVersion() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        quiz.addQuestion("Q?", 20, List.of(new Option("A", true), new Option("B", false)));
        adapter.save(quiz);
        entityManager.clear();
        Quiz loaded = adapter.findById(quiz.id()).orElseThrow();
        entityManager.clear();
        statistics.clear();

        adapter.save(loaded);

        // UPDATE version, then load quiz + questions + options. No UPDATE/DELETE/INSERT of unchanged rows.
        // (The mapper's clear() + addAll() does mark the two collections dirty, so Hibernate's
        // collection-update statistic is 2, but diffing them element by element finds nothing to write.)
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        assertThat(statistics.getEntityUpdateCount()).isZero();
    }

    @Test
    void removingTheFirstQuestionAndAddingOneRenumbersPositions() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        List<Option> options = List.of(new Option("A", true), new Option("B", false));
        Question first = quiz.addQuestion("First?", 20, options);
        Question second = quiz.addQuestion("Second?", 20, options);
        Question third = quiz.addQuestion("Third?", 20, options);
        adapter.save(quiz);
        entityManager.clear();

        Quiz loaded = adapter.findById(quiz.id()).orElseThrow();
        loaded.removeQuestion(first.id());
        Question fourth = loaded.addQuestion("Fourth?", 20, options);
        adapter.save(loaded);
        entityManager.clear();

        assertThat(adapter.findById(quiz.id()).orElseThrow().questions())
                .extracting(Question::id).containsExactly(second.id(), third.id(), fourth.id());
        // Positions are 0..n-1 without gaps, so the deferred unique constraint held at the end.
        assertThat(positionsInDatabase(quiz.id())).containsExactly(0, 1, 2);
    }

    @Test
    void fewerOptionsDeletesTheSurplusRows() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        Question question = quiz.addQuestion("Q?", 20,
                List.of(new Option("A", true), new Option("B", false), new Option("C", false), new Option("D", false)));
        adapter.save(quiz);
        entityManager.clear();

        Quiz loaded = adapter.findById(quiz.id()).orElseThrow();
        loaded.replaceQuestion(question.id(), "Q?", 20, List.of(new Option("A", true), new Option("B", false)));
        adapter.save(loaded);
        entityManager.clear();

        assertThat(adapter.findById(quiz.id()).orElseThrow().questions().getFirst().options()).hasSize(2);
        Number optionRows = (Number) entityManager.getEntityManager()
                .createNativeQuery("select count(*) from question_options where question_id = :id")
                .setParameter("id", question.id()).getSingleResult();
        assertThat(optionRows.intValue()).isEqualTo(2);
    }

    @Test
    void publishedStatusIsStored() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        quiz.addQuestion("Q?", 20, List.of(new Option("A", true), new Option("B", false)));
        quiz.publish();

        adapter.save(quiz);
        entityManager.clear();

        assertThat(adapter.findById(quiz.id()).orElseThrow().status()).isEqualTo(Status.PUBLISHED);
    }

    @Test
    void staleCopyIsRejected() {
        Quiz quiz = Quiz.create(OWNER, "Quiz");
        adapter.save(quiz);
        entityManager.clear();
        Quiz tabA = adapter.findById(quiz.id()).orElseThrow();
        Quiz tabB = adapter.findById(quiz.id()).orElseThrow();

        tabB.rename("Renamed in tab B");
        adapter.save(tabB);
        tabA.rename("Renamed in tab A");

        assertThatThrownBy(() -> adapter.save(tabA)).isInstanceOf(QuizModifiedConcurrentlyException.class);
    }

    @Test
    void findByOwnerIdReturnsOnlyThatOwnersQuizzes() {
        UUID otherOwner = UUID.randomUUID();
        Quiz mine = Quiz.create(OWNER, "Mine");
        adapter.save(mine);
        adapter.save(Quiz.create(otherOwner, "Theirs"));
        entityManager.clear();

        assertThat(adapter.findByOwnerId(OWNER)).extracting(Quiz::id).contains(mine.id())
                .allSatisfy(id -> assertThat(adapter.findById(id).orElseThrow().ownerId()).isEqualTo(OWNER));
        assertThat(adapter.findByOwnerId(otherOwner)).extracting(Quiz::title).containsExactly("Theirs");
    }

    @Test
    void databaseEnforcesTheLimitsToo() {
        // Bypasses the domain: the CHECK constraints must still hold.
        var em = entityManager.getEntityManager();
        assertThatThrownBy(() -> em.createNativeQuery(
                        "insert into quizzes (id, owner_id, title, status, version) values (gen_random_uuid(), gen_random_uuid(), 'T', 'ARCHIVED', 0)")
                .executeUpdate())
                .hasMessageContaining("quizzes_status_check");
    }

    /**
     * Two transactions load the same quiz, then both save. Exactly one wins: the first increment locks the
     * row; the second waits, then finds the version already moved on. No test transaction here: each
     * thread needs its own, committed.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSavesOfTheSameQuizHaveExactlyOneWinner() throws Exception {
        TransactionTemplate inTransaction = new TransactionTemplate(transactionManager);
        Quiz quiz = Quiz.create(UUID.randomUUID(), "Race");
        inTransaction.execute(status -> adapter.save(quiz));

        CountDownLatch bothLoaded = new CountDownLatch(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = List.of("A", "B").stream()
                    .map(tab -> threads.submit(() -> {
                        try {
                            inTransaction.execute(status -> {
                                Quiz copy = adapter.findById(quiz.id()).orElseThrow();
                                bothLoaded.countDown();
                                await(bothLoaded); // both hold version 0 before either saves
                                copy.rename("Renamed by " + tab);
                                return adapter.save(copy);
                            });
                            return "saved";
                        } catch (QuizModifiedConcurrentlyException e) {
                            return "conflict";
                        }
                    }))
                    .toList();

            List<String> outcomes = List.of(results.get(0).get(10, TimeUnit.SECONDS),
                    results.get(1).get(10, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("saved", "conflict");
        } finally {
            threads.shutdownNow();
        }
        Long finalVersion = inTransaction.execute(status -> adapter.findById(quiz.id()).orElseThrow().version());
        assertThat(finalVersion).isEqualTo(1L);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> positionsInDatabase(UUID quizId) {
        return entityManager.getEntityManager()
                .createNativeQuery("select position from questions where quiz_id = :id order by position", Integer.class)
                .setParameter("id", quizId).getResultList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
