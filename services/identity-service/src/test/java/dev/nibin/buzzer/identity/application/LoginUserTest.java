package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.application.AccessTokenIssuer.AccessToken;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import dev.nibin.buzzer.identity.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Credential checking only. What goes into the token is AccessTokenIssuerTest's job. */
class LoginUserTest {

    private static final String PASSWORD = "correct horse";

    // Spy: real BCrypt (cost 4 for speed), but calls can be verified.
    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private final UserRepository users = mock(UserRepository.class);
    private final AccessTokenIssuer accessTokenIssuer = mock(AccessTokenIssuer.class);
    private final RefreshTokenIssuer refreshTokenIssuer = mock(RefreshTokenIssuer.class);
    private final User host = User.register("host@test.dev", passwordEncoder.encode(PASSWORD),
            Set.of(Role.HOST), Instant.now());
    private final LoginUser loginUser = new LoginUser(users, passwordEncoder, accessTokenIssuer, refreshTokenIssuer);

    @Test
    void correctPasswordStartsASessionForThatUser() {
        AccessToken issued = new AccessToken("signed.jwt.value", Duration.ofMinutes(15));
        when(users.findByEmail("host@test.dev")).thenReturn(Optional.of(host));
        when(accessTokenIssuer.issueFor(host)).thenReturn(issued);
        when(refreshTokenIssuer.startSession(host.id())).thenReturn("refresh-value");

        SessionTokens tokens = loginUser.login("  Host@Test.DEV ", PASSWORD);

        assertThat(tokens.accessToken()).isSameAs(issued);
        assertThat(tokens.refreshToken()).isEqualTo("refresh-value");
    }

    @Test
    void wrongPasswordIsInvalidCredentials() {
        when(users.findByEmail("host@test.dev")).thenReturn(Optional.of(host));

        assertThatThrownBy(() -> loginUser.login("host@test.dev", "wrong password"))
                .isInstanceOf(LoginUser.InvalidCredentialsException.class);
        verify(accessTokenIssuer, never()).issueFor(any());
        verify(refreshTokenIssuer, never()).startSession(any());
    }

    @Test
    void unknownEmailIsInvalidCredentialsAndStillRunsBcrypt() {
        when(users.findByEmail("nobody@test.dev")).thenReturn(Optional.empty());
        clearInvocations(passwordEncoder); // ignore the encode() calls made during setup

        assertThatThrownBy(() -> loginUser.login("nobody@test.dev", PASSWORD))
                .isInstanceOf(LoginUser.InvalidCredentialsException.class);
        verify(passwordEncoder).matches(eq(PASSWORD), anyString());
    }

    @Test
    void passwordOver72BytesIsInvalidCredentialsNotAnError() {
        when(users.findByEmail("host@test.dev")).thenReturn(Optional.of(host));

        // encode() throws for > 72 bytes, but matches() just returns false.
        assertThatThrownBy(() -> loginUser.login("host@test.dev", "é".repeat(40)))
                .isInstanceOf(LoginUser.InvalidCredentialsException.class);
    }
}
