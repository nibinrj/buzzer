package dev.nibin.buzzer.session.api.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CONNECT authentication with the decoder mocked (real tokens are used in SessionWebSocketTest). */
class JwtConnectInterceptorTest {

    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final JwtConnectInterceptor interceptor = new JwtConnectInterceptor(decoder);
    private final MessageChannel channel = mock(MessageChannel.class);

    @Test
    void aValidTokenBecomesTheConnectionsUserWithRoles() {
        UUID user = UUID.randomUUID();
        when(decoder.decode("good")).thenReturn(Jwt.withTokenValue("good").header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of("HOST")).build());

        Message<?> result = interceptor.preSend(frame(StompCommand.CONNECT, "Bearer good"), channel);

        Authentication authentication = (Authentication) StompHeaderAccessor.wrap(result).getUser();
        assertThat(authentication.getName()).isEqualTo(user.toString());
        // contains, not containsExactly: Spring Security 7 also adds FACTOR_BEARER ("authenticated by a bearer token").
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_HOST");
    }

    @Test
    void noAuthorizationHeaderIsRefused() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null), channel))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(decoder);
    }

    @Test
    void aNonBearerHeaderIsRefused() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Basic dXNlcjpwdw=="), channel))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void aTokenTheDecoderRejectsIsRefused() {
        when(decoder.decode(any())).thenThrow(new BadJwtException("expired"));

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer old"), channel))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void otherFramesPassUntouched() {
        Message<?> subscribe = frame(StompCommand.SUBSCRIBE, null);

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);
        verifyNoInteractions(decoder);
    }

    /** A frame as Spring builds it from the wire: mutable headers, so the interceptor can set the user. */
    private static Message<byte[]> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
