package dev.nibin.buzzer.session.config;

import dev.nibin.buzzer.session.infrastructure.redis.RedisRelayListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this instance to the broadcast relay channel (Redis SUBSCRIBE, on a connection of its own). The
 * container starts with the application context, so the instance is listening before it serves WebSocket clients.
 */
@Configuration(proxyBeanMethods = false)
public class RedisRelayConfig {

    /**
     * SyncTaskExecutor: each message is handled on the subscription's own thread, one at a time, in the order Redis
     * delivered it. So "status IN_PROGRESS" still reaches the broker before "question 0". The default executor
     * starts a new thread per message, and two threads can overtake each other. The work per message is small:
     * read the JSON and hand it to the in-memory broker.
     */
    @Bean
    RedisMessageListenerContainer relayListenerContainer(RedisConnectionFactory connectionFactory,
            RedisRelayListener relayListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(new SyncTaskExecutor());
        container.addMessageListener(relayListener, new ChannelTopic(RedisRelayListener.CHANNEL));
        return container;
    }
}
