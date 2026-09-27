package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.domain.EmailAlreadyRegisteredException;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import dev.nibin.buzzer.identity.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisterUserTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    // Spy: real BCrypt (cost 4, the minimum, for speed), but calls can be verified.
    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private final UserRepository users = mock(UserRepository.class);
    private final RegisterUser registerUser =
            new RegisterUser(users, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void storesHostWithNormalizedEmailAndHashedPassword() {
        User returned = registerUser.register("  New.Host@Test.DEV ", "correct horse");

        ArgumentCaptor<User> stored = ArgumentCaptor.forClass(User.class);
        verify(users).add(stored.capture());
        User user = stored.getValue();

        assertThat(user).isSameAs(returned);
        assertThat(user.email()).isEqualTo("new.host@test.dev");
        assertThat(user.roles()).containsExactly(Role.HOST);
        assertThat(user.passwordHash()).isNotEqualTo("correct horse");
        assertThat(passwordEncoder.matches("correct horse", user.passwordHash())).isTrue();
        assertThat(user.createdAt()).isEqualTo(NOW);
    }

    @Test
    void checksExistenceWithTheNormalizedEmail() {
        registerUser.register("Mixed@Case.dev", "correct horse");

        verify(users).existsByEmail("mixed@case.dev");
    }

    @Test
    void rejectsTakenEmailWithoutHashingOrStoring() {
        when(users.existsByEmail("taken@test.dev")).thenReturn(true);

        assertThatThrownBy(() -> registerUser.register("Taken@Test.dev", "correct horse"))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
        verify(passwordEncoder, never()).encode(anyString());
        verify(users, never()).add(any());
    }
}
