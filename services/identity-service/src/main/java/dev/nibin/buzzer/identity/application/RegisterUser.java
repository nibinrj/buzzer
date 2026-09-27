package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.domain.EmailAlreadyRegisteredException;
import dev.nibin.buzzer.identity.domain.Role;
import dev.nibin.buzzer.identity.domain.User;
import dev.nibin.buzzer.identity.domain.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;

/** Use case: create an account from an email and a raw password. */
@Service
public class RegisterUser {

    // Registered accounts host quizzes; players join with guest tokens (docs/plan.md).
    private static final Set<Role> DEFAULT_ROLES = Set.of(Role.HOST);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public RegisterUser(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * @throws EmailAlreadyRegisteredException if the (normalized) email is taken
     */
    @Transactional
    public User register(String email, String rawPassword) {
        String normalizedEmail = User.normalizeEmail(email);

        // Fast path with a clear answer, checked before the ~100 ms BCrypt hash. Under a race both
        // requests can pass this check; the unique index then rejects the second insert and add()
        // throws the same exception.
        if (users.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = User.register(normalizedEmail, passwordEncoder.encode(rawPassword), DEFAULT_ROLES,
                clock.instant());
        users.add(user);
        return user;
    }
}
