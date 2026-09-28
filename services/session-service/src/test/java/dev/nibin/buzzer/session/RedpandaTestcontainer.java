package dev.nibin.buzzer.session;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
}
