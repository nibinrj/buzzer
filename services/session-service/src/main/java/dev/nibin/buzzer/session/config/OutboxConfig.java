package dev.nibin.buzzer.session.config;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.SessionLifecycle;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The outbox's moving parts: @EnableScheduling runs OutboxPublisher's @Scheduled method, on a scheduler of its
 * own; the NewTopic beans make Spring Kafka's KafkaAdmin create the two topics this service publishes to, if they
 * don't exist yet (it never changes an existing one). KafkaTemplate itself comes from Boot's Kafka
 * auto-configuration (spring.kafka.*).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    /** The bean name OutboxPublisher's @Scheduled(scheduler = ...) refers to. */
    public static final String OUTBOX_SCHEDULER = "outboxTaskScheduler";

    /**
     * One thread, for the publisher alone. Boot creates no default scheduler here: it backs off because
     * @EnableWebSocketMessageBroker already defines one (messageBrokerTaskScheduler, the STOMP broker's), and without
     * a qualifier @Scheduled would run on that. A publisher run can take seconds while Kafka is down; it must not
     * hold a thread the WebSocket side needs. Started and shut down with the context (the bean is a lifecycle bean).
     * <p>
     * defaultCandidate = false: nothing gets this bean by type, only by its name (@Scheduled's scheduler = ...).
     * Without it, Boot's WebSocket auto-configuration found it as the context's only AsyncTaskExecutor and ran both
     * STOMP channels on this one thread: every answer, ack and broadcast queued behind the publisher, and the game
     * froze whenever Kafka was down. Excluded, Boot finds no executor to hand out, and the channels keep Spring's own
     * (clientInboundChannelExecutor, clientOutboundChannelExecutor: pools of their own). StompExecutorTest checks it.
     */
    @Bean(name = OUTBOX_SCHEDULER, defaultCandidate = false)
    ThreadPoolTaskScheduler outboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("outbox-");
        return scheduler;
    }

    @Bean
    NewTopic answerSubmittedTopic(OutboxProperties properties) {
        return topic(AnswerSubmitted.TOPIC, properties);
    }

    @Bean
    NewTopic sessionLifecycleTopic(OutboxProperties properties) {
        return topic(SessionLifecycle.TOPIC, properties);
    }

    private static NewTopic topic(String name, OutboxProperties properties) {
        return TopicBuilder.name(name)
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicas())
                .build();
    }
}
