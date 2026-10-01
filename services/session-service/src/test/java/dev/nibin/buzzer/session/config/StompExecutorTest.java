package dev.nibin.buzzer.session.config;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which threads STOMP runs on. Boot's WebSocket auto-configuration hands both client channels an AsyncTaskExecutor
 * from the context if it finds one (the only one, or one named applicationTaskExecutor). The outbox's one-thread
 * scheduler must never be it (OutboxConfig: defaultCandidate = false); with nothing to hand out, each channel keeps
 * the pool Spring's STOMP configuration gives it.
 */
@ApiIntegrationTest
class StompExecutorTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void eachStompChannelRunsOnSpringsOwnPoolAndNeverOnTheOutboxScheduler() {
        Executor inbound = channelExecutor("clientInboundChannel");
        Executor outbound = channelExecutor("clientOutboundChannel");
        Object outboxScheduler = context.getBean(OutboxConfig.OUTBOX_SCHEDULER);

        assertThat(inbound).isNotSameAs(outboxScheduler).isSameAs(context.getBean("clientInboundChannelExecutor"));
        assertThat(outbound).isNotSameAs(outboxScheduler).isSameAs(context.getBean("clientOutboundChannelExecutor"));
    }

    private Executor channelExecutor(String channel) {
        return context.getBean(channel, ExecutorSubscribableChannel.class).getExecutor();
    }
}
