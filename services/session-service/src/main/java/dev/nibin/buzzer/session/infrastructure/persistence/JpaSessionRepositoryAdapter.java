package dev.nibin.buzzer.session.infrastructure.persistence;

import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.RoomCodeTakenException;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Implements the domain's SessionRepository port with Spring Data JPA. */
@Component
class JpaSessionRepositoryAdapter implements SessionRepository {

    // Unique constraint from V1__sessions.sql.
    private static final String ROOM_CODE_UNIQUE = "sessions_room_code_uk";

    private final SessionJpaRepository jpaRepository;

    JpaSessionRepositoryAdapter(SessionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    /**
     * No "is this code free?" check first: two hosts could both pass it and then collide anyway. The unique
     * constraint is the only check that holds under concurrency, so we insert and translate its violation.
     */
    @Override
    @Transactional
    public void add(Session session) {
        try {
            // Flush now so a unique violation surfaces here, not later at commit.
            jpaRepository.saveAndFlush(toEntity(session));
        } catch (DataIntegrityViolationException e) {
            if (violatesRoomCodeUnique(e)) {
                throw new RoomCodeTakenException(session.roomCode());
            }
            throw e;
        }
    }

    // Transactional so the lazy questions can be loaded while mapping (open-in-view is off).
    @Override
    @Transactional(readOnly = true)
    public Optional<Session> findById(UUID id) {
        return jpaRepository.findById(id).map(JpaSessionRepositoryAdapter::toDomain);
    }

    // MANDATORY: a lock outside a transaction would be released the moment this method returns, protecting
    // nothing. Calling this without a transaction fails loudly instead of silently not locking.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Session> findByRoomCodeForUpdate(RoomCode roomCode) {
        return jpaRepository.findByRoomCode(roomCode.value()).map(JpaSessionRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Session> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id).map(JpaSessionRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateStatus(Session session) {
        if (jpaRepository.updateStatus(session.id(), session.status()) != 1) {
            throw new IllegalStateException("Session " + session.id() + " not found for status update");
        }
    }

    private static boolean violatesRoomCodeUnique(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return ROOM_CODE_UNIQUE.equals(violation.getConstraintName());
            }
        }
        return false;
    }

    private static SessionJpaEntity toEntity(Session session) {
        return new SessionJpaEntity(session.id(), session.roomCode().value(), session.quizId(), session.hostId(),
                session.quizTitle(), session.status(), session.createdAt(),
                session.questions().stream()
                        .map(question -> new SessionQuestionEmbeddable(question.questionId(), question.text(),
                                question.timeLimitSeconds(), question.options(), question.correctOption()))
                        .toList());
    }

    private static Session toDomain(SessionJpaEntity entity) {
        return new Session(entity.getId(), new RoomCode(entity.getRoomCode()), entity.getQuizId(),
                entity.getHostId(), entity.getQuizTitle(), entity.getStatus(), entity.getCreatedAt(),
                entity.getQuestions().stream()
                        .map(question -> new SessionQuestion(question.getQuestionId(), question.getText(),
                                question.getTimeLimitSeconds(), question.getOptions(),
                                question.getCorrectOption()))
                        .toList());
    }
}
