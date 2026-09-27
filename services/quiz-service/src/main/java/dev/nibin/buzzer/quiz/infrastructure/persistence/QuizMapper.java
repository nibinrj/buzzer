package dev.nibin.buzzer.quiz.infrastructure.persistence;

import dev.nibin.buzzer.quiz.domain.Option;
import dev.nibin.buzzer.quiz.domain.Question;
import dev.nibin.buzzer.quiz.domain.Quiz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Converts between the domain Quiz aggregate and its JPA entities. Updates are applied to the managed
 * entities in place (not by building a new graph and merging), so Hibernate only writes what changed.
 */
public final class QuizMapper {

    private QuizMapper() {
    }

    public static QuizJpaEntity toNewEntity(Quiz quiz) {
        QuizJpaEntity entity = new QuizJpaEntity(quiz.id(), quiz.ownerId(), quiz.title(), quiz.status(), quiz.version());
        applyQuestions(entity, quiz.questions());
        return entity;
    }

    /** Copies the domain's state onto a managed entity: kept questions are updated, new ones added, removed ones orphaned. */
    public static void update(QuizJpaEntity entity, Quiz quiz) {
        entity.update(quiz.title(), quiz.status());
        applyQuestions(entity, quiz.questions());
    }

    public static Quiz toDomain(QuizJpaEntity entity) {
        List<Question> questions = entity.getQuestions().stream()
                .map(question -> new Question(question.getId(), question.getText(), question.getTimeLimitSeconds(),
                        question.getOptions().stream().map(o -> new Option(o.text(), o.correct())).toList()))
                .toList();
        return new Quiz(entity.getId(), entity.getOwnerId(), entity.getTitle(), entity.getStatus(), questions,
                entity.getVersion());
    }

    private static void applyQuestions(QuizJpaEntity entity, List<Question> questions) {
        Map<UUID, QuestionJpaEntity> existing = entity.getQuestions().stream()
                .collect(Collectors.toMap(QuestionJpaEntity::getId, Function.identity()));
        List<QuestionJpaEntity> ordered = new ArrayList<>();
        for (int position = 0; position < questions.size(); position++) {
            Question question = questions.get(position);
            List<OptionEmbeddable> options = question.options().stream()
                    .map(option -> new OptionEmbeddable(option.text(), option.correct()))
                    .toList();
            QuestionJpaEntity questionEntity = existing.get(question.id());
            if (questionEntity == null) {
                questionEntity = new QuestionJpaEntity(question.id(), entity, position, question.text(),
                        question.timeLimitSeconds(), options);
            } else {
                questionEntity.update(position, question.text(), question.timeLimitSeconds(), options);
            }
            ordered.add(questionEntity);
        }
        entity.replaceQuestions(ordered);
    }
}
