package dev.nibin.buzzer.session.config;

import dev.nibin.buzzer.session.api.websocket.DestinationAuthorizationInterceptor;
import dev.nibin.buzzer.session.api.websocket.JwtConnectInterceptor;
import dev.nibin.buzzer.session.api.websocket.StompLogContextInterceptor;
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
    private final TaskScheduler messageBrokerTaskScheduler;

    /**
     * messageBrokerTaskScheduler is a bean that @EnableWebSocketMessageBroker itself defines. @Lazy because this
     * configurer is needed while that bean is being set up: inject a proxy now, resolve it on first use.
     * (The pattern from the Spring reference docs for simple-broker heartbeats.)
     */
    public WebSocketConfig(WebSocketProperties properties, JwtConnectInterceptor jwtConnectInterceptor,
            DestinationAuthorizationInterceptor destinationAuthorization, StompLogContextInterceptor logContext,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.properties = properties;
        this.jwtConnectInterceptor = jwtConnectInterceptor;
        this.destinationAuthorization = destinationAuthorization;
        this.logContext = logContext;
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(properties.allowedOriginPatterns().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(messageBrokerTaskScheduler);
    }

    /**
     * Order matters: authenticate on CONNECT first, then check destinations of every later frame. logContext acts
     * later, on the thread that handles the frame (beforeHandle), so it sees the user the first one set.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtConnectInterceptor, destinationAuthorization, logContext);
    }
}
