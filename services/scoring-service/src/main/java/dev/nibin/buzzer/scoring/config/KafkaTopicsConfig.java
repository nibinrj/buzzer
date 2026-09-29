package dev.nibin.buzzer.scoring.config;

import dev.nibin.buzzer.events.ScoreUpdated;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The one topic this service owns: KafkaAdmin creates it at startup if it doesn't exist (it never changes an
 * existing one). The topics it consumes belong to session-service; the retry and DLT topics are created by the
 * retry-topic support from @RetryableTopic.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicsConfig {

    @Bean
    NewTopic scoreUpdatedTopic(@Value("${scoring.score-updates.topic-partitions}") int partitions,
            @Value("${scoring.score-updates.topic-replicas}") int replicas) {
        return TopicBuilder.name(ScoreUpdated.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
