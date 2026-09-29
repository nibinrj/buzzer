package dev.nibin.buzzer.session;

import dev.nibin.buzzer.events.ScoreUpdated;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * A real Kafka-compatible broker for the outbox: Redpanda, the same image as docker-compose.yml. @ServiceConnection
 * points spring.kafka.bootstrap-servers at it (Boot's RedpandaContainerConnectionDetailsFactory), so no address is
 * configured anywhere. In every @ApiIntegrationTest context, because OutboxPublisher runs in all of them.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedpandaTestcontainer {

    @Bean
    @ServiceConnection
    RedpandaContainer redpandaContainer() {
        return new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.2.3");
    }

    /**
     * scoring.score-updated belongs to scoring-service, which creates it with 3 partitions. Here nobody else does,
     * so the test creates it the same way before ScoreUpdatedListener subscribes.
     */
    @Bean
    NewTopic scoreUpdatedTopic() {
        return TopicBuilder.name(ScoreUpdated.TOPIC).partitions(3).replicas(1).build();
    }
}
