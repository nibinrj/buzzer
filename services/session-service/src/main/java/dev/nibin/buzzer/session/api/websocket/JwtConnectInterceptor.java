package dev.nibin.buzzer.session.api.websocket;

import dev.nibin.buzzer.session.config.SecurityConfig;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/**
 * Authenticates a STOMP connection on its CONNECT frame. Browsers can't put a header on the WebSocket handshake,
 * so the token travels in the frame instead, as the native header {@code Authorization: Bearer <jwt>}.
 * <p>
 * Same verification as the REST API (identity's JWKS, issuer, expiry, roles → ROLE_*). The resulting
 * Authentication is attached to the connection: Spring then puts it on every later frame of this connection, and
 * its name (the JWT sub) is the user for /user/... destinations. A missing or bad token throws, and the client
 * receives an ERROR frame instead of CONNECTED.
 * <p>
 * Known limit: the token is checked once, here. A connection opened with a valid token stays open after that token
 * expires (guest tokens live 3 h, about one game).
 */
@Component
public class JwtConnectInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter authenticationConverter = SecurityConfig.jwtAuthenticationConverter();

    public JwtConnectInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // The mutable accessor of this very message: setUser() changes the message that continues down the chain.
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
        }
        return message;
    }

    private AbstractAuthenticationToken authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new BadCredentialsException("CONNECT needs an Authorization: Bearer <token> header");
        }
        try {
            return authenticationConverter.convert(jwtDecoder.decode(authorization.substring(BEARER.length())));
        } catch (JwtException e) {
            // Never log or echo the token itself.
            throw new BadCredentialsException("Invalid or expired token", e);
        }
    }
}
