package dev.nibin.buzzer.quiz.infrastructure.persistence;

import dev.nibin.buzzer.quiz.domain.Quiz;
import dev.nibin.buzzer.quiz.domain.QuizModifiedConcurrentlyException;
import dev.nibin.buzzer.quiz.domain.QuizRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the domain's QuizRepository port with Spring Data JPA.
 * <p>
 * Optimistic locking for the whole aggregate: every save of an existing quiz first increments its version
 * with a compare-and-set UPDATE. That fails (0 rows) if anyone saved since this Quiz was loaded, even if
 * they only changed a question. The UPDATE also locks the quiz row until commit, so two concurrent saves
 * of the same quiz are serialized: the second waits, then sees the new version and fails.
 */
@Component
class JpaQuizRepositoryAdapter implements QuizRepository {

    private final QuizJpaRepository jpaRepository;

    JpaQuizRepositoryAdapter(QuizJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public Quiz save(Quiz quiz) {
        QuizJpaEntity entity;
        if (jpaRepository.incrementVersion(quiz.id(), quiz.version()) == 1) {
            // Loaded after the increment (the bulk UPDATE cleared the persistence context), so it has the new version.
            entity = jpaRepository.findById(quiz.id()).orElseThrow();
            QuizMapper.update(entity, quiz);
        } else if (quiz.version() == 0 && !jpaRepository.existsById(quiz.id())) {
            entity = jpaRepository.save(QuizMapper.toNewEntity(quiz)); // Persistable: persist, no SELECT first
        } else {
            throw new QuizModifiedConcurrentlyException(quiz.id());
        }
        // Write now so constraint violations surface here, not at commit.
        jpaRepository.flush();
        return QuizMapper.toDomain(entity);
    }

    // Transactional so the lazy questions/options can be loaded while mapping (open-in-view is off).
    @Override
    @Transactional(readOnly = true)
    public Optional<Quiz> findById(UUID id) {
        return jpaRepository.findById(id).map(QuizMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Quiz> findByOwnerId(UUID ownerId) {
        return jpaRepository.findByOwnerId(ownerId).stream().map(QuizMapper::toDomain).toList();
    }
}
