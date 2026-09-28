package dev.nibin.buzzer.session.infrastructure.persistence;

import dev.nibin.buzzer.session.domain.Answer;
import dev.nibin.buzzer.session.domain.AnswerRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Implements the domain's AnswerRepository port with Spring Data JPA. Joins the caller's transaction if any. */
@Component
class JpaAnswerRepositoryAdapter implements AnswerRepository {

    private final AnswerJpaRepository jpaRepository;

    JpaAnswerRepositoryAdapter(AnswerJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void add(Answer answer) {
        // Flush now: inside a caller's transaction, a unique-key violation then surfaces here, not at its commit.
        jpaRepository.saveAndFlush(new AnswerJpaEntity(answer.answerId(), answer.sessionId(), answer.questionId(),
                answer.playerId(), answer.optionIndex(), answer.correct(), answer.seq(), answer.correctRank(),
                answer.answeredAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Answer> find(UUID sessionId, UUID questionId, UUID playerId) {
        return jpaRepository.findBySessionIdAndQuestionIdAndPlayerId(sessionId, questionId, playerId)
                .map(JpaAnswerRepositoryAdapter::toDomain);
    }

    private static Answer toDomain(AnswerJpaEntity entity) {
        return new Answer(entity.getId(), entity.getSessionId(), entity.getQuestionId(), entity.getPlayerId(),
                entity.getOptionIndex(), entity.isCorrect(), entity.getSeq(), entity.getCorrectRank(),
                entity.getAnsweredAt());
    }
}
