package dev.nibin.buzzer.identity.application;

import dev.nibin.buzzer.identity.domain.User;
import dev.nibin.buzzer.identity.domain.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use case: exchange email + password for a signed RS256 access token and a refresh token. */
@Service
public class LoginUser {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;

    // Checked when the email is unknown, so that path costs the same BCrypt time as a wrong password.
    private final String dummyHash;

    public LoginUser(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.dummyHash = passwordEncoder.encode("dummy password for unknown emails");
    }

    /**
     * Starts a new session: an access token plus the first refresh token of a new family.
     *
     * @throws InvalidCredentialsException for an unknown email or a wrong password (deliberately the same)
     */
    @Transactional
    public SessionTokens login(String email, String rawPassword) {
        User user = users.findByEmail(User.normalizeEmail(email)).orElse(null);

        // Always run exactly one BCrypt check, whether or not the user exists.
        String hash = user != null ? user.passwordHash() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hash);

        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return new SessionTokens(accessTokenIssuer.issueFor(user), refreshTokenIssuer.startSession(user.id()));
    }

    public static class InvalidCredentialsException extends RuntimeException {

        public InvalidCredentialsException() {
            super("Invalid email or password");
        }
    }
}
