package dev.nibin.buzzer.scoring;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.SessionLifecycle;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * A real Kafka-compatible broker: Redpanda, the same image as docker-compose.yml. @ServiceConnection points
 * spring.kafka.bootstrap-servers at it (Boot's RedpandaContainerConnectionDetailsFactory).
 * <p>
 * The two topics scoring consumes belong to session-service, which creates them with 3 partitions. Here nobody else
 * does, so the test creates them the same way (KafkaAdmin creates NewTopic beans before the listeners start).
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedpandaTestcontainer {

    @Bean
    @ServiceConnection
    RedpandaContainer redpandaContainer() {
        return new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.2.3");
    }

    @Bean
    NewTopic answerSubmittedTopic() {
        return TopicBuilder.name(AnswerSubmitted.TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic sessionLifecycleTopic() {
        return TopicBuilder.name(SessionLifecycle.TOPIC).partitions(3).replicas(1).build();
    }
}
