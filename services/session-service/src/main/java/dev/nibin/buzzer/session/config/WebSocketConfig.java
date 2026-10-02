package dev.nibin.buzzer.session.config;

import dev.nibin.buzzer.session.api.websocket.AckLatencyInterceptor;
import dev.nibin.buzzer.session.api.websocket.AnswerReceivedInterceptor;
import dev.nibin.buzzer.session.api.websocket.DestinationAuthorizationInterceptor;
import dev.nibin.buzzer.session.api.websocket.JwtConnectInterceptor;
import dev.nibin.buzzer.session.api.websocket.StompLogContextInterceptor;
import dev.nibin.buzzer.session.api.websocket.StompObservationInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket.
 * <pre>
 * endpoint   /ws                   plain WebSocket (no SockJS); authentication happens on CONNECT, not here
 * /app/...   → @MessageMapping     commands: SessionCommandController (host), AnswerController (players)
 * /topic/... → simple broker       one-to-many (a session's question, status, reveal)
 * /queue/... → simple broker       one-to-one, addressed as /user/queue/... (errors, answer acks: to the sender)
 * </pre>
 * Heartbeats every 10 s both ways: an idle connection still carries a byte well inside the AWS ALB's idle
 * timeout, and a dead client is noticed within about 20 s.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_MILLIS = 10_000;

    private final WebSocketProperties properties;
    private final JwtConnectInterceptor jwtConnectInterceptor;
    private final DestinationAuthorizationInterceptor destinationAuthorization;
    private final StompLogContextInterceptor logContext;
    private final StompObservationInterceptor observation;
    private final AnswerReceivedInterceptor answerReceived;
    private final AckLatencyInterceptor ackLatency;
    private final TaskScheduler messageBrokerTaskScheduler;

    /**
     * messageBrokerTaskScheduler is a bean that @EnableWebSocketMessageBroker itself defines. @Lazy because this
     * configurer is needed while that bean is being set up: inject a proxy now, resolve it on first use.
     * (The pattern from the Spring reference docs for simple-broker heartbeats.)
     */
    public WebSocketConfig(WebSocketProperties properties, JwtConnectInterceptor jwtConnectInterceptor,
            DestinationAuthorizationInterceptor destinationAuthorization, StompLogContextInterceptor logContext,
            StompObservationInterceptor observation,
            AnswerReceivedInterceptor answerReceived, AckLatencyInterceptor ackLatency,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.properties = properties;
        this.jwtConnectInterceptor = jwtConnectInterceptor;
        this.destinationAuthorization = destinationAuthorization;
        this.logContext = logContext;
        this.observation = observation;
        this.answerReceived = answerReceived;
        this.ackLatency = ackLatency;
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    /**
     * No setPreserveReceiveOrder(true), on purpose: it hands each frame to the inbound channel from another thread, so
     * an interceptor's refusal no longer reaches StompSubProtocolHandler, and the client gets no ERROR frame (the
     * refusal tests in SessionWebSocketTest go silent). One client's frames may therefore be handled in parallel;
     * the game doesn't depend on their order (commands check state in Postgres and Redis, answers are ordered by Redis).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(properties.allowedOriginPatterns().toArray(String[]::new));
    }

    /**
     * preservePublishOrder: the outbound channel runs on a thread pool, so two messages to one client could otherwise
     * be written in either order. With it, each connection gets its messages in the order they were sent (a question
     * before its reveal), while different connections are still written in parallel.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setPreservePublishOrder(true);
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(messageBrokerTaskScheduler);
    }

    /**
     * Order matters: stamp an answer's arrival before anything else runs, authenticate on CONNECT, then check
     * destinations of every later frame. observation and logContext act later, on the thread that handles the frame
     * (beforeHandle), so they see the user the first ones set; observation first, so the MDC already has the traceId
     * when logContext adds the session.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(answerReceived, jwtConnectInterceptor, destinationAuthorization, observation,
                logContext);
    }

    /** ackLatency stops the answer's clock once its ack has been handed to the WebSocket. */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(ackLatency);
    }
}
